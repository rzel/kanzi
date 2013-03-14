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

const (
	TOP        = int64(1 << 48)
	BOTTOM     = int64((1 << 40) - 1)
	MAX_RANGE  = BOTTOM + 1
	MASK       = int64(0x00FFFFFFFFFFFFFF)
	NB_SYMBOLS = 257 //256 + EOF
	LAST       = NB_SYMBOLS - 1
)

type RangeEncoder struct {
	low       int64
	range_    int64
	flushed   bool
	baseFreq  []int64
	deltaFreq []int64
	bitstream kanzi.OutputBitStream
	written   bool
}

func NewRangeEncoder(bs kanzi.OutputBitStream) (*RangeEncoder, error) {
	if bs == nil {
		return nil, errors.New("Bit stream parameter cannot be null")
	}

	this := new(RangeEncoder)
	this.range_ = (TOP << 8) - 1
	this.bitstream = bs

	// Since the frequency update after each byte encoded is the bottleneck,
	// split the frequency table into an array of absolute frequencies (with
	// indexes multiple of 16) and delta frequencies (relative to the previous
	// absolute frequency) with indexes in the [0..15] range
	this.deltaFreq = make([]int64, NB_SYMBOLS+1)
	this.baseFreq = make([]int64, (NB_SYMBOLS>>4)+1)

	for i := range this.deltaFreq {
		this.deltaFreq[i] = int64(i & 15) // DELTA
	}

	for i := range this.baseFreq {
		this.baseFreq[i] = int64(i << 4) // BASE
	}

	return this, nil
}

// This method is on the speed critical path (called for each byte)
// The speed optimization is focused on reducing the frequency table update
func (this *RangeEncoder) EncodeByte(b byte) error {
	value := int(b)
	symbolLow := this.baseFreq[value>>4] + this.deltaFreq[value]
	symbolHigh := this.baseFreq[(value+1)>>4] + this.deltaFreq[value+1]
	this.range_ /= (this.baseFreq[NB_SYMBOLS>>4] + this.deltaFreq[NB_SYMBOLS])

	// Encode symbol
	this.low += (symbolLow * this.range_)
	this.range_ *= (symbolHigh - symbolLow)

	checkRange := (this.low ^ (this.low + this.range_)) & MASK

	// If the left-most digits are the same throughout the range, write bits to bitstream
	for checkRange < TOP || this.range_ < MAX_RANGE {
		// Normalize
		if checkRange >= TOP {
			this.range_ = (-this.low & MASK) & BOTTOM
		}

		this.bitstream.WriteBits(uint64((this.low>>48)&0xFF), 8)
		this.range_ <<= 8
		this.low <<= 8
		checkRange = (this.low ^ (this.low + this.range_)) & MASK
	}

	// Update frequencies: computational bottleneck !!!
	this.updateFrequencies(int(value + 1))
	this.written = true
	return nil
}

func (this *RangeEncoder) updateFrequencies(value int) {
	freq := this.baseFreq // alias
	start := (value + 15) >> 4
	length := len(freq)

	// Update absolute frequencies
	for j := start; j < length; j++ {
		freq[j]++
	}

	freq = this.deltaFreq // alias

	// Update relative frequencies (in the 'right' segment only)
	for j := (start << 4) - 1; j >= value; j-- {
		freq[j]++
	}
}

func (this *RangeEncoder) Encode(block []byte) (int, error) {
	return EntropyEncodeArray(this, block)
}

func (this *RangeEncoder) BitStream() kanzi.OutputBitStream {
	return this.bitstream
}

func (this *RangeEncoder) Dispose() {
	if this.written == true && this.flushed == false {
		// After this call the frequency tables may not be up to date
		this.flushed = true

		for i := 0; i < 7; i++ {
			this.bitstream.WriteBits(uint64((this.low>>48)&0xFF), 8)
			this.low <<= 8
		}

		this.bitstream.Flush()
	}
}

type RangeDecoder struct {
	code        int64
	low         int64
	range_      int64
	baseFreq    []int64
	deltaFreq   []int64
	initialized bool
	bitstream   kanzi.InputBitStream
}

func NewRangeDecoder(bs kanzi.InputBitStream) (*RangeDecoder, error) {
	if bs == nil {
		return nil, errors.New("Bit stream parameter cannot be null")
	}

	this := new(RangeDecoder)
	this.range_ = (TOP << 8) - 1
	this.bitstream = bs

	// Since the frequency update after each byte encoded is the bottleneck,
	// split the frequency table into an array of absolute frequencies (with
	// indexes multiple of 16) and delta frequencies (relative to the previous
	// absolute frequency) with indexes in the [0..15] range
	this.deltaFreq = make([]int64, NB_SYMBOLS+1)
	this.baseFreq = make([]int64, (NB_SYMBOLS>>4)+1)

	for i := range this.deltaFreq {
		this.deltaFreq[i] = int64(i & 15) // DELTA
	}

	for i := range this.baseFreq {
		this.baseFreq[i] = int64(i << 4) // BASE
	}

	return this, nil
}

// This method is on the speed critical path (called for each byte)
// The speed optimization is focused on reducing the frequency table update
func (this *RangeDecoder) DecodeByte() (byte, error) {
	if this.initialized == false {
		this.initialized = true
		read, err := this.bitstream.ReadBits(56)

		if err != nil {
			return 0, err
		}

		this.code = int64(read)
	}

	bfreq := this.baseFreq  // alias
	dfreq := this.deltaFreq // alias
	this.range_ /= (bfreq[NB_SYMBOLS>>4] + dfreq[NB_SYMBOLS])
	count := (this.code - this.low) / this.range_

	// Find first frequency less than 'count'
	value := len(bfreq) - 1

	for value > 0 && count < bfreq[value] {
		value--
	}

	count -= bfreq[value]
	value <<= 4

	if count > 0 {
		end := value
		value += 15

		if value > NB_SYMBOLS {
			value = NB_SYMBOLS
		}

		for value >= end && count < dfreq[value] {
			value--
		}
	}

	if value == LAST {
		more, err := this.bitstream.HasMoreToRead()

		if err != nil {
			return 0, err
		}

		if more == false {
			return 0, errors.New("End of bitstream")
		}

		errMsg := fmt.Sprintf("Unknown symbol: %d", value)
		return 0, errors.New(errMsg)
	}

	symbolLow := bfreq[value>>4] + dfreq[value]
	symbolHigh := bfreq[(value+1)>>4] + dfreq[value+1]

	// Decode symbol
	this.low += (symbolLow * this.range_)
	this.range_ *= (symbolHigh - symbolLow)

	checkRange := (this.low ^ (this.low + this.range_)) & MASK

	for checkRange < TOP || this.range_ < BOTTOM {
		// Normalize
		if checkRange >= TOP {
			this.range_ = (-this.low & MASK) & BOTTOM
		}

		read, err := this.bitstream.ReadBits(8)

		if err != nil {
			return 0, err
		}

		this.code <<= 8
		this.code |= int64(read)
		this.range_ <<= 8
		this.low <<= 8
		checkRange = (this.low ^ (this.low + this.range_)) & MASK
	}

	// Update frequencies: computational bottleneck !!!
	this.updateFrequencies(int(value + 1))
	return byte(value & 0xFF), nil
}

func (this *RangeDecoder) updateFrequencies(value int) {
	freq := this.baseFreq // alias
	start := (value + 15) >> 4
	length := len(freq)

	// Update absolute frequencies
	for j := start; j < length; j++ {
		freq[j]++
	}

	freq = this.deltaFreq // alias

	// Update relative frequencies (in the 'right' segment only)
	for j := (start << 4) - 1; j >= value; j-- {
		freq[j]++
	}
}

func (this *RangeDecoder) Decode(block []byte) (int, error) {
	return EntropyDecodeArray(this, block)
}

func (this *RangeDecoder) BitStream() kanzi.InputBitStream {
	return this.bitstream
}

func (this *RangeDecoder) Dispose() {
}
