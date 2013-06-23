/*
Copyright 2011-2013 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package io

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"kanzi"
	"kanzi/bitstream"
	"kanzi/entropy"
	"kanzi/function"
	"kanzi/util"
)

// Write to/read from stream using a 2 step process:
// Encoding:
// - step 1: a ByteFunction is used to reduce the size of the input data (bytes input & output)
// - step 2: an EntropyEncoder is used to entropy code the results of step 1 (bytes input, bits output)
// Decoding is the exact reverse process.

const (
	BITSTREAM_TYPE           = 0x4B414E5A // "KANZ"
	BITSTREAM_FORMAT_VERSION = 2
	DEFAULT_BUFFER_SIZE      = 1024 * 1024
	COPY_LENGTH_MASK         = 0x0F
	SMALL_BLOCK_MASK         = 0x80
	SKIP_FUNCTION_MASK       = 0x40
	MIN_BLOCK_SIZE           = 1024
	MAX_BLOCK_SIZE           = (16 * 1024 * 1024) - 4
	SMALL_BLOCK_SIZE         = 15

	ERR_MISSING_FILENAME    = -1
	ERR_BLOCK_SIZE          = -2
	ERR_INVALID_CODEC       = -3
	ERR_CREATE_COMPRESSOR   = -4
	ERR_CREATE_DECOMPRESSOR = -5
	ERR_OUTPUT_IS_DIR       = -6
	ERR_OVERWRITE_FILE      = -7
	ERR_CREATE_FILE         = -8
	ERR_CREATE_BITSTREAM    = -9
	ERR_OPEN_FILE           = -10
	ERR_READ_FILE           = -11
	ERR_WRITE_FILE          = -12
	ERR_PROCESS_BLOCK       = -13
	ERR_CREATE_CODEC        = -14
	ERR_INVALID_FILE        = -15
	ERR_STREAM_VERSION      = -16
	ERR_UNKNOWN             = -127
)

var (
	EMPTY_BYTE_SLICE = make([]byte, 0)
)

type IOError struct {
	msg  string
	code int
}

func NewIOError(msg string, code int) *IOError {
	this := new(IOError)
	this.msg = msg
	this.code = code
	return this
}

// Implement error interface
func (this IOError) Error() string {
	return fmt.Sprintf("%v: %v", this.msg, this.code)
}

func (this IOError) Message() string {
	return this.msg
}

func (this IOError) ErrorCode() int {
	return this.code
}

type CompressedOutputStream struct {
	blockSize     uint
	hasher        *util.XXHash
	buffer1       []byte
	buffer2       []byte
	entropyType   byte
	transformType byte
	obs           kanzi.OutputBitStream
	debugWriter   io.Writer
	initialized   bool
	closed        bool
	blockId       int
	curIdx        int
}

func NewCompressedOutputStream(entropyCodec string, functionType string, os kanzi.OutputStream, blockSize uint,
	checksum bool, debugWriter io.Writer) (*CompressedOutputStream, error) {
	if os == nil {
		return nil, errors.New("Invalid null output stream parameter")
	}

	if blockSize > MAX_BLOCK_SIZE {
		errMsg := fmt.Sprintf("The block size must be at most %d)", MAX_BLOCK_SIZE)
		return nil, errors.New(errMsg)
	}

	if blockSize < MIN_BLOCK_SIZE {
		errMsg := fmt.Sprintf("The block size must be at least %d)", MIN_BLOCK_SIZE)
		return nil, errors.New(errMsg)
	}

	this := new(CompressedOutputStream)
	var err error

	if this.obs, err = bitstream.NewDefaultOutputBitStream(os, blockSize); err != nil {
		return this, err
	}

	eType := entropyCodec[0]

	// Check entropy type validity
	if _, err = entropy.GetEntropyCodecName(eType); err != nil {
		return this, NewIOError(err.Error(), ERR_CREATE_CODEC)
	}

	this.entropyType = eType

	fType := functionType[0]

	// Check transform type validity
	if _, err = function.GetByteFunctionName(fType); err != nil {
		return this, err
	}

	this.transformType = fType
	this.blockSize = blockSize

	if checksum == true {
		this.hasher, err = util.NewXXHash(BITSTREAM_TYPE)

		if err != nil {
			return this, err
		}
	}

	this.buffer1 = make([]byte, blockSize)
	this.buffer2 = EMPTY_BYTE_SLICE
	this.debugWriter = debugWriter
	return this, nil
}

func (this *CompressedOutputStream) WriteHeader() *IOError {
	if this.initialized == true {
		return nil
	}

	var err error

	if _, err = this.obs.WriteBits(BITSTREAM_TYPE, 32); err != nil {
		return NewIOError("Cannot write bitstream type in header", ERR_WRITE_FILE)
	}

	if _, err = this.obs.WriteBits(BITSTREAM_FORMAT_VERSION, 7); err != nil {
		return NewIOError("Cannot write bitstream version in header", ERR_WRITE_FILE)
	}

	cksum := 0

	if this.hasher != nil {
		cksum = 1
	}

	if err = this.obs.WriteBit(cksum); err != nil {
		return NewIOError("Cannot write checksum in header", ERR_WRITE_FILE)
	}

	if _, err = this.obs.WriteBits(uint64(this.entropyType&0x7F), 7); err != nil {
		return NewIOError("Cannot write entropy type in header", ERR_WRITE_FILE)
	}

	if _, err = this.obs.WriteBits(uint64(this.transformType&0x7F), 7); err != nil {
		return NewIOError("Cannot write transform type in header", ERR_WRITE_FILE)
	}

	if _, err = this.obs.WriteBits(uint64(this.blockSize), 26); err != nil {
		return NewIOError("Cannot write block size header", ERR_WRITE_FILE)
	}

	return nil
}

// Implement the kanzi.OutputStream interface
func (this *CompressedOutputStream) Write(array []byte) (int, error) {
	startChunk := 0
	lenChunk := len(array) - startChunk

	if lenChunk+this.curIdx >= int(this.blockSize) {
		// Limit to number of available bytes
		lenChunk = int(this.blockSize) - this.curIdx
	}

	for lenChunk > 0 {
		copy(this.buffer1[this.curIdx:], array[startChunk:startChunk+lenChunk])
		this.curIdx += lenChunk
		startChunk += lenChunk

		if this.curIdx >= int(this.blockSize) {
			// Buffer full, time to encode
			if err := this.processBlock(); err != nil {
				return startChunk, err
			}
		}

		lenChunk = len(array) - startChunk

		if lenChunk+this.curIdx >= int(this.blockSize) {
			// Limit to number of available bytes
			lenChunk = int(this.blockSize) - this.curIdx
		}
	}

	return len(array), nil
}

// Implement the kanzi.OutputStream interface
func (this *CompressedOutputStream) Sync() error {
	// Let the embedded bitstream take care of the flushing
	return nil
}

// Implement the kanzi.OutputStream interface
func (this *CompressedOutputStream) Close() error {
	if this.closed == true {
		return nil
	}

	this.closed = true

	if this.curIdx > 0 {
		if err := this.processBlock(); err != nil {
			return err
		}
	}

	// End block of size 0
	this.obs.WriteBits(SMALL_BLOCK_MASK, 8)
	this.obs.Close()
	
	// Release resources
	this.buffer1 = EMPTY_BYTE_SLICE
	this.buffer2 = EMPTY_BYTE_SLICE
	return nil
}

func (this *CompressedOutputStream) processBlock() error {
	if this.curIdx == 0 {
		return nil
	}

	if this.initialized == false {
		if err := this.WriteHeader(); err != nil {
			return err
		}

		this.initialized = true
	}

	if err := this.encode(this.buffer1[0:this.curIdx]); err != nil {
		return err
	}

	this.curIdx = 0
	this.blockId++
	return nil
}

// Return the number of bytes written so far
func (this *CompressedOutputStream) GetWritten() uint64 {
	return (this.obs.Written() + 7) >> 3
}

func (this *CompressedOutputStream) encode(data []byte) error {
	blockLength := uint(len(data))

	if this.transformType == 'N' {
		this.buffer2 = data // share buffers if no transform
	} else if len(this.buffer2) < int(blockLength*5/4) { // ad-hoc size
		this.buffer2 = make([]byte, blockLength*5/4)
	}

	transform, err := function.NewByteFunction(blockLength, this.transformType)

	if err != nil {
		return NewIOError(err.Error(), ERR_CREATE_CODEC)
	}

	mode := byte(0)
	dataSize := uint(0)
	compressedLength := blockLength
	checksum := uint32(0)
	iIdx := uint(0)
	oIdx := uint(0)

	if blockLength <= SMALL_BLOCK_SIZE {
		// Just copy
		if !bytes.Equal(this.buffer2, data) {
			copy(this.buffer2, data[0:blockLength])
		}

		iIdx += blockLength
		oIdx += blockLength
		mode = byte(SMALL_BLOCK_SIZE | (blockLength & COPY_LENGTH_MASK))
	} else {
		// Compute block checksum
		if this.hasher != nil {
			checksum = this.hasher.Hash(data[0:blockLength])
		}

		iIdx, oIdx, err = transform.Forward(data, this.buffer2)

		if err != nil || iIdx < blockLength {
			// Transform failed or did not compress, skip and copy
			copy(this.buffer2, data)
			iIdx = blockLength
			oIdx = blockLength
			mode |= SKIP_FUNCTION_MASK
		}

		compressedLength = oIdx
		dataSize++

		for i := uint(0xFF); i < compressedLength; i <<= 8 {
			dataSize++
		}

		// Record size of 'block size' in bytes
		mode |= byte(dataSize & 0x03)
	}

	// Each block is encoded separately
	// Rebuild the entropy encoder to reset block statistics
	ee, err := entropy.NewEntropyEncoder(this.obs, this.entropyType)

	if err != nil {
		return NewIOError(err.Error(), ERR_CREATE_CODEC)
	}

	defer ee.Dispose()

	// Write block 'header' (mode + compressed length)
	bs := ee.BitStream()
	written := bs.Written()
	bs.WriteBits(uint64(mode), 8)

	if dataSize > 0 {
		if _, err = bs.WriteBits(uint64(compressedLength), 8*dataSize); err != nil {
			return NewIOError(err.Error(), ERR_WRITE_FILE)
		}
	}

	// Write checksum (unless small block)
	if (this.hasher != nil) && (mode&SMALL_BLOCK_MASK == 0) {
		if _, err = bs.WriteBits(uint64(checksum), 32); err != nil {
			return NewIOError(err.Error(), ERR_WRITE_FILE)
		}
	}

	// Entropy encode block
	encoded, err := ee.Encode(this.buffer2[0:compressedLength])

	if err != nil {
		return NewIOError(err.Error(), ERR_PROCESS_BLOCK)
	}

	// Print info if debug writer is not nil
	if this.debugWriter != nil {
		fmt.Fprintf(this.debugWriter, "Block %d: %d => %d => %d (%d%%)", this.blockId,
			blockLength, encoded, (bs.Written()-written)/8,
			(bs.Written()-written)*100/uint64(blockLength*8))

		if (this.hasher != nil) && (mode&SMALL_BLOCK_MASK == 0) {
			fmt.Fprintf(this.debugWriter, "  [%x]", checksum)
		}

		fmt.Fprintln(this.debugWriter, "")
	}

	// Reset buffer in case another block uses a different transform
	if this.transformType == 'N' {
		this.buffer2 = EMPTY_BYTE_SLICE
	}

	return nil
}

type CompressedInputStream struct {
	blockSize     uint
	hasher        *util.XXHash
	buffer1       []byte
	buffer2       []byte
	entropyType   byte
	transformType byte
	ibs           kanzi.InputBitStream
	debugWriter   io.Writer
	initialized   bool
	closed        bool
	blockId       int
	maxIdx        int
	curIdx        int
}

func NewCompressedInputStream(is kanzi.InputStream,
	debugWriter io.Writer) (*CompressedInputStream, error) {
	if is == nil {
		return nil, errors.New("Invalid null input stream parameter")
	}

	this := new(CompressedInputStream)
	this.buffer1 = make([]byte, 0)
	this.buffer2 = make([]byte, 0)
	this.debugWriter = debugWriter
	var err error

	if this.ibs, err = bitstream.NewDefaultInputBitStream(is, DEFAULT_BUFFER_SIZE); err != nil {
		errMsg := fmt.Sprintf("Cannot create input bit stream: %v", err)
		return nil, NewIOError(errMsg, ERR_CREATE_BITSTREAM)
	}

	return this, err
}

func (this *CompressedInputStream) ReadHeader() error {
	if this.initialized == true {
		return nil
	}

	fileType := uint64(0)
	var err error

	// Read stream type
	if fileType, err = this.ibs.ReadBits(32); err != nil {
		errMsg := fmt.Sprintf("Error reading stream type in header from input file: %v", err)
		return NewIOError(errMsg, ERR_READ_FILE)
	}

	// Sanity check
	if fileType != BITSTREAM_TYPE {
		errMsg := fmt.Sprintf("Invalid stream type: expected %#x, got %#x", BITSTREAM_TYPE, fileType)
		return NewIOError(errMsg, ERR_INVALID_FILE)
	}

	header := uint64(0)

	if header, err = this.ibs.ReadBits(48); err != nil {
		errMsg := fmt.Sprintf("Error reading header in input file: %v", err)
		return NewIOError(errMsg, ERR_READ_FILE)
	}

	version := int((header >> 41) & 0x7F)

	// Sanity check
	if version != BITSTREAM_FORMAT_VERSION {
		errMsg := fmt.Sprintf("Cannot read this version of the stream: %d", version)
		return NewIOError(errMsg, ERR_STREAM_VERSION)
	}

	// Read block checksum
	checksum := (header >> 40) & 1

	if checksum == 1 {
		if this.hasher, err = util.NewXXHash(BITSTREAM_TYPE); err != nil {
			return err
		}
	}

	// Read entropy codec
	this.entropyType = byte((header >> 33) & 0x7F)

	// Read transform
	this.transformType = byte((header >> 26) & 0x7F)

	// Read block size
	this.blockSize = uint(header & uint64(0x03FFFFFF))

	if this.blockSize > MAX_BLOCK_SIZE {
		errMsg := fmt.Sprintf("Invalid block size read from file: %d", this.blockSize)
		return NewIOError(errMsg, ERR_BLOCK_SIZE)
	}

	if this.debugWriter != nil {
		fmt.Fprintf(this.debugWriter, "Checksum set to %v\n", (this.hasher != nil))
		fmt.Fprintf(this.debugWriter, "Block size set to %d\n", this.blockSize)
		w1, err := function.GetByteFunctionName(this.transformType)

		if err != nil {
			errMsg := fmt.Sprintf("Invalid transform type: %d", this.blockSize)
			return NewIOError(errMsg, ERR_INVALID_CODEC)
		}

		if w1 == "NONE" {
			w1 = "no"
		}

		fmt.Fprintf(this.debugWriter, "Using %v transform (stage 1)\n", w1)
		w2, err := entropy.GetEntropyCodecName(this.entropyType)

		if w2 == "NONE" {
			w2 = "no"
		}

		if err != nil {
			errMsg := fmt.Sprintf("Invalid entropy codec type: %d", this.blockSize)
			return NewIOError(errMsg, ERR_INVALID_CODEC)
		}

		fmt.Fprintf(this.debugWriter, "Using %v entropy codec (stage 2)\n", w2)
	}

	return nil
}

// Implement kanzi.InputStream interface
func (this *CompressedInputStream) Close() error {
	if this.closed == true {
		return nil
	}

	this.closed = true

	if _, err := this.ibs.Close(); err != nil {
		return err
	}

	// Release resources
	this.buffer1 = EMPTY_BYTE_SLICE
	this.buffer2 = EMPTY_BYTE_SLICE
	this.maxIdx = 0
	return nil
}

// Implement kanzi.InputStream interface
func (this *CompressedInputStream) Read(array []byte) (int, error) {
	startChunk := 0

	for true {
		if this.curIdx >= this.maxIdx {
			var err error

			// Buffer empty, time to decode
			if this.maxIdx, err = this.processBlock(); err != nil {
				return startChunk, err
			}

			if this.maxIdx == 0 {
				// Reached end of stream
				return startChunk, nil
			}
		}

		lenChunk := len(array) - startChunk

		if lenChunk+this.curIdx >= this.maxIdx {
			// Limit to number of available bytes in buffer
			lenChunk = this.maxIdx - this.curIdx
		}

		if lenChunk == 0 {
			break
		}

		copy(array[startChunk:], this.buffer1[this.curIdx:this.curIdx+lenChunk])
		this.curIdx += lenChunk
		startChunk += lenChunk
	}

	return len(array), nil
}

func (this *CompressedInputStream) processBlock() (int, error) {
	if this.initialized == false {
		if err := this.ReadHeader(); err != nil {
			return 0, err
		}

		this.initialized = true
	}

	if len(this.buffer1) < int(this.blockSize) {
		this.buffer1 = make([]byte, this.blockSize)
	}

	decoded, err := this.decode(this.buffer1)

	if err != nil {
		return decoded, err
	}

	this.curIdx = 0
	this.blockId++
	return decoded, nil
}

// Return the number of bytes read so far
func (this *CompressedInputStream) GetRead() uint64 {
	return (this.ibs.Read() + 7) >> 3
}

func (this *CompressedInputStream) decode(data []byte) (int, error) {
	// Each block is decoded separately
	// Rebuild the entropy decoder to reset block statistics
	ed, err := entropy.NewEntropyDecoder(this.ibs, this.entropyType)

	if err != nil {
		return 0, NewIOError(err.Error(), ERR_INVALID_CODEC)
	}

	defer ed.Dispose()

	// Extract header directly from bitstream
	bs := ed.BitStream()
	read := bs.Read()
	r, err := bs.ReadBits(8)

	if err != nil {
		return 0, NewIOError(err.Error(), ERR_READ_FILE)
	}

	mode := byte(r)
	var compressedLength uint
	checksum1 := uint32(0)

	if (mode & SMALL_BLOCK_MASK) != 0 {
		compressedLength = uint(mode & COPY_LENGTH_MASK)
	} else {
		dataSize := uint(mode & 0x03)
		length := dataSize << 3
		mask := uint64(1<<length) - 1

		if r, err = bs.ReadBits(length); err != nil {
			return 0, NewIOError(err.Error(), ERR_READ_FILE)
		}

		compressedLength = uint(r & mask)
	}

	if compressedLength == 0 {
		return 0, nil
	}

	if compressedLength > MAX_BLOCK_SIZE {
		errMsg := fmt.Sprintf("Invalid compressed block length: %d", compressedLength)
		return 0, NewIOError(errMsg, ERR_BLOCK_SIZE)
	}

	// Extract checksum from bit stream (if any)
	if (this.hasher != nil) && (mode&SMALL_BLOCK_MASK) == 0 {
		if r, err = bs.ReadBits(32); err != nil {
			return 0, NewIOError(err.Error(), ERR_READ_FILE)
		}

		checksum1 = uint32(r)
	}

	if this.transformType == 'N' {
		this.buffer2 = data // share buffers if no transform
	} else if len(this.buffer2) < int(this.blockSize) {
		this.buffer2 = make([]byte, this.blockSize)
	}

	// Block entropy decode
	_, err = ed.Decode(this.buffer2[0:compressedLength])

	if err != nil {
		return 0, NewIOError(err.Error(), ERR_PROCESS_BLOCK)
	}

	var decoded int

	if ((mode & SMALL_BLOCK_MASK) != 0) || ((mode & SKIP_FUNCTION_MASK) != 0) {
		if !bytes.Equal(this.buffer2, data) {
			copy(data, this.buffer2[0:compressedLength])
		}

		decoded = int(compressedLength)
	} else {
		// Each block is decoded separately
		// Rebuild the entropy decoder to reset block statistics
		transform, err := function.NewByteFunction(compressedLength, this.transformType)

		if err != nil {
			return 0, NewIOError(err.Error(), ERR_INVALID_CODEC)
		}

		var oIdx uint

		// Inverse transform
		if _, oIdx, err = transform.Inverse(this.buffer2, data); err != nil {
			return 0, NewIOError(err.Error(), ERR_PROCESS_BLOCK)
		}

		decoded = int(oIdx)
		
		// Print info if debug writer is not nil
		if this.debugWriter != nil {
			fmt.Fprintf(this.debugWriter, "Block %d: %d => %d => %d", this.blockId,
				(bs.Read()-read)/8, compressedLength, decoded)

			if (this.hasher != nil) && (mode&SMALL_BLOCK_MASK == 0) {
				fmt.Fprintf(this.debugWriter, "  [%x]", checksum1)
			}

			fmt.Fprintln(this.debugWriter, "")
		}

		// Verify checksum (unless small block)
		if (this.hasher != nil) && ((mode & SMALL_BLOCK_MASK) == 0) {
			checksum2 := this.hasher.Hash(data[0:decoded])

			if checksum2 != checksum1 {
				errMsg := fmt.Sprintf("Invalid checksum: expected %x, found %x", checksum1, checksum2)
				return decoded, NewIOError(errMsg, ERR_PROCESS_BLOCK)
			}
		}

	}

	// Reset buffer in case another block uses a different transform
	if this.transformType == 'N' {
		this.buffer2 = EMPTY_BYTE_SLICE
	}

	return decoded, nil
}
