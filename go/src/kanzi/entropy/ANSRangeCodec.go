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

package entropy

import (
	"errors"
	"fmt"
	"kanzi"
)

// Implementation of Asymetric Numeral System codec.
// See "Asymetric Numeral System" by Jarek Duda at http://arxiv.org/abs/0902.0271
// For alternate C implementation examples, see https://github.com/Cyan4973/FiniteStateEntropy
// and https://github.com/rygorous/ryg_rans

const (
	ANS_TOP                = uint64(1) << 24
	DEFAULT_ANS_CHUNK_SIZE = uint(0) // full size of block
	DEFAULT_ANS_LOG_RANGE  = uint(13)
)

type ANSRangeEncoder struct {
	bitstream kanzi.OutputBitStream
	freqs     []int
	cumFreqs  []int
	alphabet  []byte
	buffer    []int32
	eu        *EntropyUtils
	chunkSize int
	logRange  uint
}

// The chunk size indicates how many bytes are encoded (per block) before
// resetting the frequency stats. 0 means that frequencies calculated at the
// beginning of the block apply to the whole block
// Since the number of args is variable, this function can be called like this:
// NewANSRangeEncoder(bs) or NewANSRangeEncoder(bs, 16384, 14)
func NewANSRangeEncoder(bs kanzi.OutputBitStream, args ...uint) (*ANSRangeEncoder, error) {
	if bs == nil {
		return nil, errors.New("Invalid null bitstream parameter")
	}

	if len(args) > 2 {
		return nil, errors.New("At most one chunk size and one log range can be provided")
	}

	chkSize := DEFAULT_ANS_CHUNK_SIZE
	logRange := DEFAULT_ANS_LOG_RANGE

	if len(args) == 2 {
		chkSize = args[0]
		logRange = args[1]
	}

	if chkSize != 0 && chkSize < 1024 {
		return nil, errors.New("The chunk size must be at least 1024")
	}

	if chkSize > 1<<30 {
		return nil, errors.New("The chunk size must be at most 2^30")
	}

	if logRange < 8 || logRange > 16 {
		return nil, fmt.Errorf("Invalid range parameter: %v (must be in [8..16])", logRange)
	}

	this := new(ANSRangeEncoder)
	this.bitstream = bs
	this.alphabet = make([]byte, 256)
	this.freqs = make([]int, 256)
	this.cumFreqs = make([]int, 257)
	this.buffer = make([]int32, 0)
	this.logRange = logRange
	this.chunkSize = int(chkSize)
	var err error
	this.eu, err = NewEntropyUtils()
	return this, err
}

func (this *ANSRangeEncoder) updateFrequencies(frequencies []int, size int, lr uint) error {
	if frequencies == nil || len(frequencies) != 256 {
		return errors.New("Invalid frequencies parameter")
	}

	alphabetSize, err := this.eu.NormalizeFrequencies(frequencies, this.alphabet, size, lr)

	if err != nil {
		return err
	}

	this.cumFreqs[0] = 0

	// Create histogram of frequencies scaled to 'range'
	for i := 0; i < 256; i++ {
		this.cumFreqs[i+1] = this.cumFreqs[i] + frequencies[i]
	}

	this.encodeHeader(alphabetSize, this.alphabet, frequencies, lr)
	return nil
}

func (this *ANSRangeEncoder) encodeHeader(alphabetSize int, alphabet []byte, frequencies []int, lr uint) bool {
	if alphabetSize == 0 {
		return true
	}

	EncodeAlphabet(this.bitstream, alphabet[0:alphabetSize])

	// Encode frequencies
	max := 0
	logMax := uint(8)

	for i := 0; i < alphabetSize; i++ {
		if frequencies[alphabet[i]] > max {
			max = frequencies[alphabet[i]]
		}
	}

	for 1<<logMax <= max {
		logMax++
	}

	this.bitstream.WriteBits(uint64(lr-8), 3) // logRange
	this.bitstream.WriteBits(uint64(logMax-8), 5)

	// Write all frequencies but the first one
	// The first frequency (usually high for symbol 0) is ignored since the sum is known
	for i := 1; i < alphabetSize; i++ {
		this.bitstream.WriteBits(uint64(frequencies[alphabet[i]]), logMax)
	}

	return true
}

// Dynamically compute the frequencies for every chunk of data in the block
func (this *ANSRangeEncoder) Encode(block []byte) (int, error) {
	if block == nil {
		return 0, errors.New("Invalid null block parameter")
	}

	if len(block) == 0 {
		return 0, nil
	}

	sizeChunk := this.chunkSize

	if sizeChunk == 0 {
		sizeChunk = len(block)
	}

	frequencies := this.freqs // aliasing
	endChunk := len(block)
	lr := this.logRange

	// Lower log range if the size of the data block is small
	for lr > 8 && 1<<lr > len(block) {
		lr--
	}

	top := (ANS_TOP >> lr) << 32
	st := ANS_TOP

	if len(this.buffer) < sizeChunk {
		this.buffer = make([]int32, sizeChunk)
	}

	// Work backwards
	for endChunk > 0 {
		for i := range frequencies {
			frequencies[i] = 0
		}

		startChunk := endChunk - sizeChunk

		if startChunk < 0 {
			startChunk = 0
		}

		for i := startChunk; i < endChunk; i++ {
			frequencies[block[i]]++
		}

		// Rebuild statistics
		this.updateFrequencies(frequencies, endChunk-startChunk, lr)
		n := 0

		// Reverse encoding
		for i := endChunk - 1; i >= startChunk; i-- {
			symbol := block[i]
			freq := uint64(frequencies[symbol])
			max := top * freq

			// Normalize
			for st >= max {
				this.buffer[n] = int32(st)
				n++
				st >>= 32
			}

			// Compute next ANS state
			st = ((st / freq) << lr) + (st % freq) + uint64(this.cumFreqs[symbol])
		}

		endChunk = startChunk

		// Write final ANS state
		this.bitstream.WriteBits(st, 64)

		// Write encoded data to bitstream
		for n--; n >= 0; n-- {
			this.bitstream.WriteBits(uint64(this.buffer[n]), 32)
		}
	}

	return len(block), nil
}

func (this *ANSRangeEncoder) Dispose() {
}

func (this *ANSRangeEncoder) BitStream() kanzi.OutputBitStream {
	return this.bitstream
}

type ANSRangeDecoder struct {
	bitstream kanzi.InputBitStream
	freqs     []int
	cumFreqs  []int
	f2s       []byte // mapping frequency -> symbol
	alphabet  []byte
	chunkSize int
}

// The chunk size indicates how many bytes are encoded (per block) before
// resetting the frequency stats. 0 means that frequencies calculated at the
// beginning of the block apply to the whole block
// Since the number of args is variable, this function can be called like this:
// NewANSRangeDecoder(bs) or NewANSRangeDecoder(bs, 16384, 14)
// The default chunk size is 65536 bytes.
func NewANSRangeDecoder(bs kanzi.InputBitStream, args ...uint) (*ANSRangeDecoder, error) {
	if bs == nil {
		return nil, errors.New("Invalid null bitstream parameter")
	}

	if len(args) > 1 {
		return nil, errors.New("At most one chunk size can be provided")
	}

	chkSize := DEFAULT_ANS_CHUNK_SIZE

	if len(args) == 1 {
		chkSize = args[0]
	}

	if chkSize != 0 && chkSize < 1024 {
		return nil, errors.New("The chunk size must be at least 1024")
	}

	if chkSize > 1<<30 {
		return nil, errors.New("The chunk size must be at most 2^30")
	}

	this := new(ANSRangeDecoder)
	this.bitstream = bs
	this.alphabet = make([]byte, 256)
	this.freqs = make([]int, 256)
	this.cumFreqs = make([]int, 257)
	this.f2s = make([]byte, 0)
	this.chunkSize = int(chkSize)
	return this, nil
}

func (this *ANSRangeDecoder) decodeHeader(frequencies []int) (int, uint, error) {
	alphabetSize, err := DecodeAlphabet(this.bitstream, this.alphabet)

	if err != nil || alphabetSize == 0 {
		return alphabetSize, 0, nil
	}

	if alphabetSize != 256 {
		for i := range frequencies {
			frequencies[i] = 0
		}
	}

	// Decode frequencies
	logRange := uint(8 + this.bitstream.ReadBits(3))
	logMax := uint(8 + this.bitstream.ReadBits(5))
	sum := 0

	// Read all frequencies but the first one
	for i := 1; i < alphabetSize; i++ {
		val := int(this.bitstream.ReadBits(logMax))
		frequencies[this.alphabet[i]] = val
		sum += val
	}

	// Infer first frequency
	frequencies[this.alphabet[0]] = (1 << logRange) - sum
	this.cumFreqs[0] = 0

	if len(this.f2s) < 1<<logRange {
		this.f2s = make([]byte, 1<<logRange)
	}

	// Create histogram of frequencies scaled to 'range' and reverse mapping
	for i := 0; i < 256; i++ {
		this.cumFreqs[i+1] = this.cumFreqs[i] + frequencies[i]

		for j := frequencies[i] - 1; j >= 0; j-- {
			this.f2s[this.cumFreqs[i]+j] = byte(i)
		}
	}

	return alphabetSize, logRange, nil
}

func (this *ANSRangeDecoder) Decode(block []byte) (int, error) {
	if block == nil {
		return 0, errors.New("Invalid null block parameter")
	}

	if len(block) == 0 {
		return 0, nil
	}

	end := len(block)
	startChunk := 0
	sizeChunk := this.chunkSize

	if sizeChunk == 0 {
		sizeChunk = len(block)
	}

	for startChunk < end {
		alphabetSize, logRange, err := this.decodeHeader(this.freqs)

		if err != nil || alphabetSize == 0 {
			return startChunk, err
		}

		mask := (uint64(1) << logRange) - 1
		endChunk := startChunk + sizeChunk

		if endChunk > end {
			endChunk = end
		}

		// Read initial ANS state
		st := this.bitstream.ReadBits(64)

		for i := startChunk; i < endChunk; i++ {
			idx := int(st & mask)
			symbol := this.f2s[idx]
			block[i] = symbol

			// Compute next state
			st = uint64(this.freqs[symbol])*(st>>logRange) + uint64(idx-this.cumFreqs[symbol])

			// Normalize
			for st < ANS_TOP {
				st = (st << 32) | this.bitstream.ReadBits(32)
			}
		}

		startChunk = endChunk
	}

	return len(block), nil
}

func (this *ANSRangeDecoder) BitStream() kanzi.InputBitStream {
	return this.bitstream
}

func (this *ANSRangeDecoder) Dispose() {
}
