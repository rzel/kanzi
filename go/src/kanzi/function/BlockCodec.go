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

package function

import (
	"errors"
	"fmt"
	"kanzi"
	"kanzi/transform"
)

// Utility class to compress/decompress a data block
// Fast reversible block coder/decoder based on a pipeline of transformations:
// Forward: Burrows-Wheeler -> Move to Front -> Zero Length
// Inverse: Zero Length -> Move to Front -> Burrows-Wheeler
// The block size determine the balance between speed and compression ratio
// The max block size is around 250 KB and provides the best compression ratio.
// The default block size provides a good balance.

// Stream format: Header (m bytes) Data (n bytes)
// Header: mode (4 bits) + header data size (4 bits) + compressed data length (8, 16 or 24 bits)
//         + BWT primary index (8, 16 or 24 bits)
//         or mode (1 bit) + block size (7 bits)
// * If mode & 0x80 != 0 then the block is not compressed, just copied.
//   and the block length is contained in the 7 lower digits
//   Hence a 0 byte block (use to mark end of stream) is 0x80
// * Else, the first 4 Most Significant Bits are used to encode extra information.
//   The next 4 bits encode the size (in bytes) of the compressed data length 
//   (the same size is used for the BWT primary index)
//
// EG: Mode=0x85 block copy, length = 5 bytes followed by block data
//     Mode=0x03 regular transform followed by 24 bit compressed length, 24 bit BWT index, block data
//     Mode=0x02 regular transform followed by 16 bit compressed length, 16 bit BWT index, block data
//     Mode & 0x2? != 0 no RLE
//     Mode & 0x4? != 0 no ZLE

const (
	COPY_LENGTH_MASK      = 0x7F
	COPY_BLOCK_MASK       = 0x80
	NO_RLT_MASK           = 0x20
	NO_ZLT_MASK           = 0x40
	MAX_BLOCK_HEADER_SIZE = 7
	MAX_BLOCK_SIZE        = 16*1024*1024 - MAX_BLOCK_HEADER_SIZE // 16 MB (24 bits)
)

type BlockCodec struct {
	buffer []byte
	mtft   *transform.MTFT
	bwt    *transform.BWT
	size   uint
}

func NewBlockCodec(size uint) (*BlockCodec, error) {
	if size > MAX_BLOCK_SIZE {
		errMsg := fmt.Sprintf("The block size must be at most %d\n", MAX_BLOCK_SIZE)
		return nil, errors.New(errMsg)
	}

	var err error
	this := new(BlockCodec)
	this.bwt, err = transform.NewBWT(0)

	if err != nil {
		return nil, err
	}

	this.mtft, err = transform.NewMTFT(0)

	if err != nil {
		return nil, err
	}

	this.size = size
	this.buffer = make([]byte, size)
	return this, nil
}

func (this *BlockCodec) Size() uint {
	return this.size
}

func (this *BlockCodec) SetSize(sz uint) bool {
	if sz > MAX_BLOCK_SIZE {
		return false
	}

	this.size = sz
	return true
}

func (this *BlockCodec) Forward(src, dst []byte) (uint, uint, error) {
	if src == nil {
		return 0, 0, errors.New("Input block cannot be null")
	}

	if dst == nil {
		return 0, 0, errors.New("Output block cannot be null")
	}

	if &src == &dst {
		return 0, 0, errors.New("Input and dst blocks cannot be equal")
	}

	blockSize := this.size

	if this.size == 0 {
		blockSize = uint(len(src))
	}

	if blockSize > MAX_BLOCK_SIZE {
		errMsg := fmt.Sprintf("Block length is %v, max value is %v", blockSize, MAX_BLOCK_SIZE)
		return 0, 0, errors.New(errMsg)
	}

	if blockSize > uint(len(src)) {
		errMsg := fmt.Sprintf("Block length is %v, max value is %v", blockSize, len(src))
		return 0, 0, errors.New(errMsg)
	}

	if blockSize < 16 {
		// Since processing the block data will hardly overcome the data added
		// due to the header, use a short header and simply copy the block
		if uint(len(dst)) < blockSize+1 {
			errMsg := fmt.Sprintf("Output block length: %v, required: %v", len(dst), blockSize+1)
			return 0, 0, errors.New(errMsg)
		}

		// Add 'mode' byte
		dst[0] = byte(COPY_BLOCK_MASK | int(blockSize))

		// Copy block   
		if blockSize != 0 {
			copy(dst[1:], src[0:blockSize])
		}

		return blockSize, blockSize + 1, nil
	}

	mode := byte(NO_RLT_MASK)

	// Apply Burrows-Wheeler Transform
	this.bwt.SetSize(blockSize)
	this.bwt.Forward(src)
	primaryIndex := this.bwt.PrimaryIndex()

	// Apply Move-To-Front Transform
	this.mtft.SetSize(blockSize)
	this.mtft.Forward(src)

	headerDataSize := uint(1) // in bytes

	if blockSize > 0xFF {
		headerDataSize++
	}

	if blockSize > 0xFFFF {
		headerDataSize++
	}

	headerSize := 1 + headerDataSize + headerDataSize
	mode |= byte(headerDataSize)
	zlt, err := NewZLT(blockSize)

	if err != nil {
		return 0, 0, err
	}

	// Apply Zero Length Encoding 
	iIdx, oIdx, err := zlt.Forward(src, dst[headerSize:])

	compressedLength := oIdx
	oIdx += headerSize

	if err != nil {
		return iIdx, oIdx, err
	}

	// If the ZLE did not compress (it can expand in some pathological cases)
	// then revert
	if iIdx < blockSize || oIdx > blockSize {
		// Not enough room in dst buffer => return error
		if uint(len(dst)) < headerSize+blockSize {
			errMsg := fmt.Sprint("Output block length: %v, required: %v", len(dst), headerSize+blockSize)
			return iIdx, oIdx, errors.New(errMsg)
		}

		copy(dst[headerSize:], src[0:blockSize])
		mode |= byte(NO_ZLT_MASK)
	}

	// Write block header
	dst[0] = mode
	shift := uint(headerDataSize-1) << 3
	idx := uint(1)

	for i := uint(0); i < headerDataSize; i++ {
		dst[idx] = byte((compressedLength >> shift) & 0xFF)
		dst[headerDataSize+idx] = byte((primaryIndex >> shift) & 0xFF)
		shift -= 8
		idx++
	}

	return iIdx, oIdx, nil
}

func (this *BlockCodec) Inverse(src, dst []byte) (uint, uint, error) {
	mode := src[0]

	if mode&COPY_BLOCK_MASK != 0 {
		// Extract block length
		length := int(mode & COPY_LENGTH_MASK)

		if len(dst) < length {
			errMsg := fmt.Sprintf("Output block length: %d, required: %d", len(dst), length)
			return 0, 0, errors.New(errMsg)
		}

		// Just copy (small) block
		copy(dst, src[1:length+1])
		return uint(length + 1), uint(length), nil
	}

	// Extract compressed length
	headerDataSize := uint(mode & 0x0F)
	compressedLength := uint(src[1])
	iIdx := uint(2)

	if headerDataSize > 1 {
		compressedLength = (compressedLength << 8) | uint(src[iIdx])
		iIdx++
	}

	if headerDataSize > 2 {
		compressedLength = (compressedLength << 8) | uint(src[iIdx])
		iIdx++
	}

	// Extract BWT primary index 
	primaryIndex := uint(src[iIdx]) & 0xFF
	iIdx++

	if headerDataSize > 1 {
		primaryIndex = (primaryIndex << 8) | uint(src[iIdx])
		iIdx++
	}

	if headerDataSize > 2 {
		primaryIndex = (primaryIndex << 8) | uint(src[iIdx])
		iIdx++
	}

	headerSize := 1 + headerDataSize + headerDataSize
	boIdx := uint(0)

	if mode&NO_ZLT_MASK == 0 {
		// Apply Zero Length Decoding 
		zlt, err := NewZLT(compressedLength)

		if err != nil {
			return iIdx, 0, err
		}

		// The size after decompression is not known, let us assume that the output
		// is big enough, otherwise return an error after decompression
		// To be safe the size of the output should be set to the max size allowed
		if len(this.buffer) < len(dst) {
			this.buffer = make([]byte, len(dst))
		}

		iIdx, boIdx, err = zlt.Inverse(src[headerSize:], this.buffer)

		if err != nil {
			return iIdx, boIdx, err
		}

		// If buffer is too small, return error 
		if iIdx < compressedLength {
			errMsg := fmt.Sprintf("Input block processed: %d, required: %d", iIdx, compressedLength)
			return iIdx, boIdx, errors.New(errMsg)
		}
	} else {
		if len(this.buffer) < int(compressedLength) {
			this.buffer = make([]byte, compressedLength)
		}

		copy(this.buffer, src[headerSize:headerSize+compressedLength])
		boIdx = compressedLength
	}

	blockSize := boIdx

	// Apply Move-To-Front Inverse Transform
	this.mtft.SetSize(blockSize)
	this.mtft.Inverse(this.buffer)

	// Apply Burrows-Wheeler Inverse Transform
	this.bwt.SetPrimaryIndex(primaryIndex)
	this.bwt.SetSize(blockSize)
	this.bwt.Inverse(this.buffer)

	oIdx := uint(0)

	if mode&NO_RLT_MASK == 0 {
		// Apply Run Length Decoding
		rlt, err := NewRLT(blockSize, DEFAULT_RLE_THRESHOLD)

		if err == nil {
			_, oIdx, err = rlt.Inverse(this.buffer, dst)
		}

		if err != nil {
			return iIdx, oIdx, err
		}

		// If output is too small, return error
		if oIdx < blockSize {
			errMsg := fmt.Sprintf("Output block processed: %d, required: %d", oIdx, blockSize)
			return iIdx, oIdx, errors.New(errMsg)
		}
	} else {
		if len(dst) < int(oIdx+blockSize) {
			errMsg := fmt.Sprintf("Output block processed: %d, required: %d", len(dst), oIdx+blockSize)
			return iIdx, oIdx, errors.New(errMsg)
		}

		copy(dst, this.buffer[0:blockSize])
		oIdx = blockSize
	}

	return iIdx, oIdx, nil
}

// Return -1 if error, otherwise the number of bytes written to the encoder
func (this *BlockCodec) Encode(data []byte, ee kanzi.EntropyEncoder) (int, error) {
	if ee == nil {
		err := errors.New("Invalid null entropy encoder\n")
		return -1, err
	}

	output := make([]byte, len(data)+MAX_BLOCK_HEADER_SIZE)
	_, _, err := this.Forward(data, output)

	if err != nil {
		return -1, err
	}

	// Extract header info and write it to the bitstream directly
	// (some entropy decoders need block data statistics before decoding a byte)
	header, err := createBlockHeaderFromArray(output)

	if err != nil {
		return -1, err
	}

	bs := ee.BitStream()
	bs.WriteBits(uint64(header.mode), 8)
	bs.WriteBits(uint64(header.blockLength), 8*header.dataSize)
	bs.WriteBits(uint64(header.primaryIndex), 8*header.dataSize)

	// Entropy encode data block
	start := (2 * header.dataSize) + 1
	end := start + header.blockLength
	encoded := 0
	encoded, err = ee.Encode(output[start:end])

	if err != nil {
		return -1, err
	}

	return encoded, nil
}

// Return -1 if error, otherwise the number of bytes read from the encoder
func (this *BlockCodec) Decode(data []byte, ed kanzi.EntropyDecoder) (int, error) {
	// Extract header directly from bitstream
	header, err := createBlockHeaderFromStream(ed.BitStream())

	if header.blockLength == 0 {
		return 0, nil
	}

	if header.blockLength > MAX_BLOCK_SIZE || err != nil {
		if err == nil {
			errMsg := fmt.Sprintf("Invalid block size: %f\n", header.blockLength)
			err = errors.New(errMsg)
		}

		return -1, err
	}

	data[0] = byte(header.mode)
	shift := uint((header.dataSize - 1) << 3)
	idx := uint(1)

	for i := uint(0); i < header.dataSize; i++ {
		data[idx] = byte((header.blockLength >> shift) & 0xFF)
		idx++
		shift -= 8
	}

	shift = uint((header.dataSize - 1) << 3)

	for i := uint(0); i < header.dataSize; i++ {
		data[idx] = byte((header.primaryIndex >> shift) & 0xFF)
		idx++
		shift -= 8
	}

	// Block entropy decode 
	start := idx
	end := start + header.blockLength
	_, err = ed.Decode(data[start:end])

	if err != nil {
		return -1, err
	}

	idx = uint(0)
	this.SetSize(header.blockLength)
	_, idx, err = this.Inverse(data, data)

	if err != nil {
		return -1, err
	}

	return int(idx), nil
}

type BWTBlockHeader struct {
	mode         uint
	blockLength  uint
	primaryIndex uint
	dataSize     uint
}

func createBlockHeaderFromArray(array []byte) (*BWTBlockHeader, error) {
	idx := 0
	this := new(BWTBlockHeader)
	this.blockLength = 0
	this.mode = uint(array[idx])
	idx++

	if this.mode&COPY_BLOCK_MASK != 0 {
		this.blockLength = this.mode & COPY_LENGTH_MASK
		this.dataSize = 0
	} else {
		this.dataSize = this.mode & 0x0F
		val := uint(array[idx])
		idx++
		this.blockLength = val

		if this.dataSize > 1 {
			val = uint(array[idx])
			idx++
			this.blockLength = (this.blockLength << 8) | val
		}

		if this.dataSize > 2 {
			val = uint(array[idx])
			idx++
			this.blockLength = (this.blockLength << 8) | val
		}

		val = uint(array[idx])
		idx++
		this.primaryIndex = val

		if this.dataSize > 1 {
			val = uint(array[idx])
			idx++
			this.primaryIndex = (this.primaryIndex << 8) | val
		}

		if this.dataSize > 2 {
			val = uint(array[idx])
			idx++
			this.primaryIndex = (this.primaryIndex << 8) | val
		}
	}

	return this, nil
}

func createBlockHeaderFromStream(bs kanzi.InputBitStream) (*BWTBlockHeader, error) {
	this := new(BWTBlockHeader)
	read, err := bs.ReadBits(8)

	if err != nil {
		return this, err
	}

	this.mode = uint(read & 0xFF)
	this.blockLength = 0

	if this.mode&COPY_BLOCK_MASK != 0 {
		this.blockLength = this.mode & COPY_LENGTH_MASK
	} else {
		this.dataSize = this.mode & 0x0F
		length := uint(8 * this.dataSize)
		mask := uint64((1 << uint(length)) - 1)
		read, err := bs.ReadBits(length)

		if err != nil {
			return this, err
		}

		this.blockLength = uint(read & mask)
		read, err = bs.ReadBits(length)

		if err != nil {
			return this, err
		}

		this.primaryIndex = uint(read & mask)
	}

	return this, nil
}
