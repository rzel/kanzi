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
	"kanzi"
)

// Based on fpaq1 by Matt Mahoney - Stationary order 0 binary entropy encoder/decoder
type FPAQPredictor struct {
	ctxIdx int
	states [512][]int16
}

// ! Requires the coder to extend input bytes to 9-bit symbols !
func NewFPAQPredictor() (*FPAQPredictor, error) {
	this := new(FPAQPredictor)
	this.ctxIdx = 1

	for i := 511; i >= 0; i-- {
		this.states[i] = make([]int16, 2)
	}

	return this, nil
}

// Used to update the probability model
func (this *FPAQPredictor) Update(bit byte) {
	st := this.states[this.ctxIdx]
	st[bit]++

	if st[bit] > 2000 {
		st[0] >>= 1
		st[1] >>= 1
	}

	if this.ctxIdx >= 256 {
		this.ctxIdx = 1
	} else {
		this.ctxIdx = (this.ctxIdx << 1) | int(bit)
	}
}

// Return the split value representing the  probability for each symbol 
// in the [0..4095] range. 
// E.G. 410 represents roughly a probability of 10% for 0
// Assume stream of 9-bit symbols   
func (this *FPAQPredictor) Get() uint {
	st := this.states[this.ctxIdx]
	num := uint(st[1]+1) << 12
	den := uint(st[0] + st[1] + 2)
	return num / den
}

type FPAQEntropyEncoder struct {
	super *BinaryEntropyEncoder
}

func NewFPAQEntropyEncoder(bs kanzi.OutputBitStream, predictor Predictor) (*FPAQEntropyEncoder, error) {
	this := new(FPAQEntropyEncoder)
	parent, err := NewBinaryEntropyEncoder(bs, predictor)

	if err == nil {
		this.super = parent
	}

	return this, err
}

func (this *FPAQEntropyEncoder) EncodeByte(val byte) error {
	this.EncodeBit(0)
	return this.super.EncodeByte(val)
}

func (this *FPAQEntropyEncoder) EncodeBit(bit byte) error {
	return this.super.EncodeBit(bit)
}

func (this *FPAQEntropyEncoder) Encode(block []byte) (int, error) {
	return EntropyEncodeArray(this, block)
}

func (this *FPAQEntropyEncoder) Flush() {
	this.super.Flush()
}

func (this *FPAQEntropyEncoder) BitStream() kanzi.OutputBitStream {
	return this.super.BitStream()
}

func (this *FPAQEntropyEncoder) Dispose() {
	this.EncodeBit(1)
	this.super.Flush()
	this.BitStream().WriteBits(0, 24)
	this.BitStream().Flush()
}

type FPAQEntropyDecoder struct {
	super *BinaryEntropyDecoder
}

func NewFPAQEntropyDecoder(bs kanzi.InputBitStream, predictor Predictor) (*FPAQEntropyDecoder, error) {
	this := new(FPAQEntropyDecoder)
	parent, err := NewBinaryEntropyDecoder(bs, predictor)

	if err == nil {
		this.super = parent
	}

	return this, err
}

func (this *FPAQEntropyDecoder) Initialized() bool {
	return this.super.Initialized()
}

func (this *FPAQEntropyDecoder) Initialize() error {
	return this.super.Initialize()
}

func (this *FPAQEntropyDecoder) DecodeByte() (byte, error) {
	// Deferred initialization: the bistream may not be ready at build time
	// Initialize 'current' with bytes read from the bitstream
	if this.Initialized() == false {
		this.Initialize()
	}

	res := 1

	// Custom logic to decode a byte
	for res < 256 {
		res <<= 1
		read, err := this.DecodeBit()

		if err != nil {
			return byte(read), err
		}

		res += int(read)
	}

	return byte(res - 256), nil
}

func (this *FPAQEntropyDecoder) DecodeBit() (int, error) {
	return this.super.DecodeBit()
}

func (this *FPAQEntropyDecoder) Decode(block []byte) (int, error) {
	if this.Initialized() == false {
		this.Initialize()
	}

	err := error(nil)
	bit := 0
	i := 0

	for i = 0; i < len(block); i++ {
		bit, err = this.DecodeBit()

		for err == nil && bit == 0 && i < len(block) {
			block[i], err = this.DecodeByte()

			if err == nil {
				i++
				bit, err = this.DecodeBit()
			}
		}
	}

	return i, err
}

func (this *FPAQEntropyDecoder) BitStream() kanzi.InputBitStream {
	return this.super.BitStream()
}

func (this *FPAQEntropyDecoder) Dispose() {
	this.super.Dispose()
}
