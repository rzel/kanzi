/*
Copyright 2011, 2012 Frederic Langlet
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
	"errors"
	"fmt"
	"io"
	"kanzi"
	"kanzi/bitstream"
	"kanzi/entropy"
	"kanzi/function"
	"strings"
)

const (
	BITSTREAM_TYPE           = 0x4B4E5A    // "KNZ"
	BITSTREAM_FORMAT_VERSION = 0

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
func (this *IOError) Error() string {
	return fmt.Sprintf("%v: %v", this.msg, this.code)
}

func (this *IOError) Message() string {
	return this.msg
}

func (this *IOError) ErrorCode() int {
	return this.code
}

type CompressedOutputStream struct {
	blockSize   uint
	bc          *function.BlockCodec
	buffer      []byte
	entropyType byte
	obs         kanzi.OutputBitStream
	debugWriter io.Writer
	initialized bool
	closed      bool
	blockId     int
	curIdx      int
}

func NewCompressedOutputStream(entropyCodec string, os kanzi.OutputStream, blockSize uint,
	debugWriter io.Writer) (*CompressedOutputStream, error) {
	if os == nil {
		return nil, errors.New("Invalid null output stream parameter")
	}

	if blockSize < 256 {
		return nil, errors.New("Invalid buffer size parameter (must be at least 256)")
	}

	if blockSize > function.MAX_BLOCK_SIZE {
		errMsg := fmt.Sprintf("Invalid buffer size parameter (must be at most %d)", function.MAX_BLOCK_SIZE)
		return nil, errors.New(errMsg)
	}

	eType := byte(0)

	switch strings.ToUpper(entropyCodec) {

	case "NONE":
		eType = 'N'

	case "HUFFMAN":
		eType = 'H'

	case "RANGE":
		eType = 'R'

	case "FPAQ":
		eType = 'F'

	case "PAQ":
		eType = 'P'

	default:
		msg := fmt.Sprintf("Invalid  entropy encoder type: %s", entropyCodec)
		return nil, NewIOError(msg, ERR_INVALID_CODEC)
	}

	this := new(CompressedOutputStream)
	this.entropyType = eType
	this.blockSize = blockSize
	this.buffer = make([]byte, blockSize)
	this.debugWriter = debugWriter
	var err error

	if this.obs, err = bitstream.NewDefaultOutputBitStream(os); err != nil {
		return this, err
	}

	this.bc, err = function.NewBlockCodec(blockSize)
	return this, err
}

func (this *CompressedOutputStream) WriteHeader() *IOError {
	if this.initialized == true {
		return nil
	}

	var err error

	if _, err = this.obs.WriteBits(BITSTREAM_TYPE, 24); err != nil {
		return NewIOError("Cannot write header", ERR_WRITE_FILE)
	}

	if _, err = this.obs.WriteBits(BITSTREAM_FORMAT_VERSION, 8); err != nil {
		return NewIOError("Cannot write header", ERR_WRITE_FILE)
	}

	if _, err = this.obs.WriteBits(uint64(this.entropyType), 8); err != nil {
		return NewIOError("Cannot write header", ERR_WRITE_FILE)
	}

	if _, err = this.obs.WriteBits(uint64(this.blockSize), 24); err != nil {
		return NewIOError("Cannot write header", ERR_WRITE_FILE)
	}

	return nil
}

// Implement the kanzi.OutputStream interface
func (this *CompressedOutputStream) Write(array []byte) (int, error) {
	startChunk := 0
	lenChunk := len(array) - startChunk

	if lenChunk+this.curIdx >= len(this.buffer) {
		// Limit to number of available bytes in buffer
		lenChunk = len(this.buffer) - this.curIdx
	}

	for lenChunk > 0 {
		copy(this.buffer[this.curIdx:], array[startChunk:startChunk+lenChunk])
		this.curIdx += lenChunk
		startChunk += lenChunk

		if this.curIdx >= len(this.buffer) {
			// Buffer full, time to encode
			if err := this.encode(); err != nil {
				return startChunk, err
			}
		}

		lenChunk = len(array) - startChunk

		if lenChunk+this.curIdx >= len(this.buffer) {
			// Limit to number of available bytes in buffer
			lenChunk = len(this.buffer) - this.curIdx
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
		if err := this.encode(); err != nil {
			return err
		}
	}

	// End block of size 0
	// The 'real' value is BlockCodec.COPY_BLOCK_MASK | (0 & BlockCodec.COPY_LENGTH_MASK)
	this.obs.WriteBits(0x80, 8)
	this.obs.Close()
	return nil
}

func (this *CompressedOutputStream) encode() error {
	if this.curIdx == 0 {
		return nil
	}

	var ee kanzi.EntropyEncoder
	var err error
	written := this.obs.Written()
	
	// Each block is encoded separately
	// Rebuild the entropy encoder to reset block statistics
	switch this.entropyType {
	case 'H':
		ee, err = entropy.NewHuffmanEncoder(this.obs)

	case 'R':
		ee, err = entropy.NewRangeEncoder(this.obs)

	case 'P':
		predictor, _ := entropy.NewPAQPredictor()
		ee, err = entropy.NewBinaryEntropyEncoder(this.obs, predictor)

	case 'F':
		predictor, _ := entropy.NewFPAQPredictor()
		ee, err = entropy.NewFPAQEntropyEncoder(this.obs, predictor)

	case 'N':
		ee, err = entropy.NewNullEntropyEncoder(this.obs)

	default:
		errMsg := fmt.Sprintf("Invalid entropy encoder: %c", this.entropyType)
		return NewIOError(errMsg, ERR_INVALID_CODEC)
	}

	if err != nil {
		errMsg := fmt.Sprintf("Failed to create entropy encoder: %v", err)
		return NewIOError(errMsg, ERR_CREATE_CODEC)
	}

	if this.initialized == false {
		if err := this.WriteHeader(); err != nil {
			return err
		}

		this.initialized = true
	}

	if len(this.buffer) < int(this.blockSize) {
		this.buffer = make([]byte, this.blockSize)
	}

	this.bc.SetSize(uint(this.curIdx))

	if encoded, err := this.bc.Encode(this.buffer, ee); encoded < 0 || err != nil {
		if err != nil {
			errMsg := fmt.Sprintf("Error in block codec forward(): %v", err)
			return NewIOError(errMsg, ERR_PROCESS_BLOCK)
		} else {
			return NewIOError("Error in block codec forward()", ERR_PROCESS_BLOCK)
		}
	}

	if this.debugWriter != nil {
		// Display the block size before and after block transform + entropy coding
		fmt.Fprintf(this.debugWriter, "Block %d: %d bytes (%d%%)\n",
			this.blockId, (this.obs.Written()-written)/8,
			(this.obs.Written()-written)*100/uint64(this.bc.Size()*8))
	}

	this.curIdx = 0
	ee.Dispose()
	this.blockId++
	return nil
}

func (this *CompressedOutputStream) GetWritten() uint64 {
	return (this.obs.Written() + 7) >> 3
}

type CompressedInputStream struct {
	blockSize   uint
	bc          *function.BlockCodec
	buffer      []byte
	entropyType byte
	ibs         kanzi.InputBitStream
	debugWriter io.Writer
	initialized bool
	closed      bool
	blockId     int
	curIdx      int
	maxIdx      int
}

func NewCompressedInputStream(is kanzi.InputStream,
	debugWriter io.Writer) (*CompressedInputStream, error) {
	if is == nil {
		return nil, errors.New("Invalid null input stream parameter")
	}

	this := new(CompressedInputStream)
	this.buffer = make([]byte, 0)
	this.debugWriter = debugWriter
	var err error

	if this.ibs, err = bitstream.NewDefaultInputBitStream(is); err != nil {
		errMsg := fmt.Sprintf("Cannot create input bit stream: %v", err)
		return nil, NewIOError(errMsg, ERR_CREATE_BITSTREAM)
	}

	this.bc, err = function.NewBlockCodec(0)
	return this, err
}

func (this *CompressedInputStream) ReadHeader() error {
	if this.initialized == true {
		return nil
	}

	// Read stream type
	fileType := uint64(0)
	var err error

	if fileType, err = this.ibs.ReadBits(24); err != nil {
		errMsg := fmt.Sprintf("Error reading header from input file: %v", err)
		return NewIOError(errMsg, ERR_READ_FILE)
	}

	// Sanity check
	if fileType != BITSTREAM_TYPE {
		errMsg := fmt.Sprintf("Invalid stream type: expected %#X, got %#X", BITSTREAM_TYPE, fileType)
		return NewIOError(errMsg, ERR_INVALID_FILE)
	}

	header := uint64(0)

	if header, err = this.ibs.ReadBits(40); err != nil {
		errMsg := fmt.Sprintf("Error reading input file: %v", err)
		return NewIOError(errMsg, ERR_READ_FILE)
	}

	version := int((header >> 32) & 0xFF)

	// Sanity check
	if version < BITSTREAM_FORMAT_VERSION {
		errMsg := fmt.Sprintf("Cannot read this version of the stream: %d", version)
		return NewIOError(errMsg, ERR_STREAM_VERSION)
	}

	this.entropyType = byte((header >> 24) & 0xFF)
	this.blockSize = uint(header & 0xFFFFFF)

	if this.blockSize > uint(function.MAX_BLOCK_SIZE) {
		errMsg := fmt.Sprintf("Invalid block size read from file: %d", this.blockSize)
		return NewIOError(errMsg, ERR_BLOCK_SIZE)
	}

	if this.debugWriter != nil {
		fmt.Fprintf(this.debugWriter, "Block size set to %d\n", this.blockSize)

		if this.entropyType == 'H' {
			fmt.Fprintln(this.debugWriter, "Using HUFFMAN entropy codec")
		} else if this.entropyType == 'R' {
			fmt.Fprintln(this.debugWriter, "Using RANGE entropy codec")
		} else if this.entropyType == 'P' {
			fmt.Fprintln(this.debugWriter, "Using PAQ entropy codec")
		} else if this.entropyType == 'F' {
			fmt.Fprintln(this.debugWriter, "Using FPAQ entropy codec")
		} else if this.entropyType == 'N' {
			fmt.Fprintln(this.debugWriter, "Using no entropy codec")
		}
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

	this.buffer = make([]byte, 0)
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
			if this.maxIdx, err = this.decode(); err != nil {
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

		copy(array[startChunk:], this.buffer[this.curIdx:this.curIdx+lenChunk])
		this.curIdx += lenChunk
		startChunk += lenChunk
	}

	return len(array), nil
}

func (this *CompressedInputStream) decode() (int, error) {
	if this.initialized == false {	
		if err := this.ReadHeader(); err != nil {
			return 0, err
		}

		this.initialized = true
	}

	var ed kanzi.EntropyDecoder
	var err error

	switch this.entropyType {
	// Each block is decoded separately
	// Rebuild the entropy decoder to reset block statistics
	case 'H':
		ed, err = entropy.NewHuffmanDecoder(this.ibs)

	case 'R':
		ed, err = entropy.NewRangeDecoder(this.ibs)

	case 'P':
		predictor, _ := entropy.NewPAQPredictor()
		ed, err = entropy.NewBinaryEntropyDecoder(this.ibs, predictor)

	case 'F':
		predictor, _ := entropy.NewFPAQPredictor()
		ed, err = entropy.NewFPAQEntropyDecoder(this.ibs, predictor)

	case 'N':
		ed, err = entropy.NewNullEntropyDecoder(this.ibs)

	default:
		errMsg := fmt.Sprintf("Unsupported entropy codec type: '%c'", this.entropyType)
		return 0, NewIOError(errMsg, ERR_INVALID_CODEC)
	}

	if err != nil {
		errMsg := fmt.Sprintf("Failed to create entropy decoder: %v", err)
		return 0, NewIOError(errMsg, ERR_CREATE_CODEC)
	}

	if len(this.buffer) < int(this.blockSize) {
		this.buffer = make([]byte, this.blockSize)
	}

	decoded, err := this.bc.Decode(this.buffer, ed)

	if err != nil {
		errMsg := fmt.Sprintf("Error in block codec inverse(): %v", err)
		return 0, NewIOError(errMsg, ERR_PROCESS_BLOCK)
	} else if decoded < 0 {
		errMsg := fmt.Sprintf("Error in block codec inverse()")
		return 0, NewIOError(errMsg, ERR_PROCESS_BLOCK)
	}

	if this.debugWriter != nil {
		// Display block size after entropy decoding + block transform
		fmt.Fprintf(this.debugWriter, "Block %d: %d byte(s)\n", this.blockId, decoded)
	}

	this.curIdx = 0
	this.blockId++
	ed.Dispose()
	return decoded, nil
}

func (this *CompressedInputStream) GetRead() uint64 {
	return (this.ibs.Read() + 7) >> 3
}
