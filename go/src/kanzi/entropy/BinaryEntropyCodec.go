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
	"kanzi"
)

type Predictor interface {
	// Used to update the probability model
	Update(bit byte)

	// Return the split value representing the probability of 1 in the [0..4095] range.
	// E.G. 410 represents roughly a probability of 10% for 1
	Get() uint
}

type BinaryEntropyEncoder struct {
	predictor Predictor
	low       uint64
	high      uint64
	bitstream kanzi.OutputBitStream
}

func NewBinaryEntropyEncoder(bs kanzi.OutputBitStream, predictor Predictor) (*BinaryEntropyEncoder, error) {
	if bs == nil {
		return nil, errors.New("Bit stream parameter cannot be null")
	}

	if predictor == nil {
		return nil, errors.New("Predictor parameter cannot be null")
	}

	this := new(BinaryEntropyEncoder)
	this.predictor = predictor
	this.low = 0
	this.high = 0xFFFFFFFFFFFFFF
	this.bitstream = bs
	return this, nil
}

func (this *BinaryEntropyEncoder) EncodeByte(val byte) error {
	for i := 7; i >= 0; i-- {
		if err := this.EncodeBit((val >> uint(i)) & 1); err != nil {
			return err
		}
	}

	return nil
}

func (this *BinaryEntropyEncoder) EncodeBit(bit byte) error {
	// Compute prediction
	prediction := this.predictor.Get()

	// Calculate interval split
	xmid := this.low + ((this.high-this.low)>>12)*uint64(prediction)

	// Update fields with new interval bounds
	if bit&1 == 1 {
		this.high = xmid
	} else {
		this.low = xmid + 1
	}

	// Update predictor
	this.predictor.Update(bit)

	// Write unchanged first 32 bits to bitstream
	for (this.low^this.high)&0xFFFFFFFF000000 == 0 {
		this.Flush()
	}

	return nil
}

func (this *BinaryEntropyEncoder) Encode(block []byte) (int, error) {
	return EntropyEncodeArray(this, block)
}

func (this *BinaryEntropyEncoder) Flush() {
	this.bitstream.WriteBits(this.high>>24, 32)
	this.low <<= 32
	this.high = (this.high << 32) | 0xFFFFFFFF
}

func (this *BinaryEntropyEncoder) BitStream() kanzi.OutputBitStream {
	return this.bitstream
}

func (this *BinaryEntropyEncoder) Dispose() {
	this.bitstream.WriteBits(this.low|0xFFFFFF, 56)
	this.bitstream.Flush()
}

type BinaryEntropyDecoder struct {
	predictor   Predictor
	low         uint64
	high        uint64
	current     uint64
	initialized bool
	bitstream   kanzi.InputBitStream
}

func NewBinaryEntropyDecoder(bs kanzi.InputBitStream, predictor Predictor) (*BinaryEntropyDecoder, error) {
	if bs == nil {
		return nil, errors.New("Bit stream parameter cannot be null")
	}

	if predictor == nil {
		return nil, errors.New("Predictor parameter cannot be null")
	}

	// Defer stream reading. We are creating the object, we should not do any I/O
	this := new(BinaryEntropyDecoder)
	this.predictor = predictor
	this.low = 0
	this.high = 0xFFFFFFFFFFFFFF
	this.current = 0
	this.initialized = false
	this.bitstream = bs
	return this, nil
}

func (this *BinaryEntropyDecoder) DecodeByte() (byte, error) {
	// Deferred initialization: the bistream may not be ready at build time
	// Initialize 'current' with bytes read from the bitstream
	if this.Initialized() == false {
		this.Initialize()
	}

	return this.decodeByte_()
}

func (this *BinaryEntropyDecoder) decodeByte_() (byte, error) {
	res := 0

	for i := 7; i >= 0; i-- {
		bit, err := this.DecodeBit()

		if err != nil {
			return 0, err
		}

		res |= (bit << uint(i))
	}

	return byte(res), nil
}

func (this *BinaryEntropyDecoder) Initialized() bool {
	return this.initialized
}

func (this *BinaryEntropyDecoder) Initialize() error {
	if this.initialized == true {
		return nil
	}

	read, err := this.bitstream.ReadBits(56)

	if err != nil {
		return err
	}

	this.current = read
	this.initialized = true
	return nil
}

func (this *BinaryEntropyDecoder) DecodeBit() (int, error) {
	// Compute prediction
	prediction := this.predictor.Get()

	// Calculate interval split
	xmid := this.low + ((this.high-this.low)>>12)*uint64(prediction)
	var bit int

	if this.current <= xmid {
		bit = 1
		this.high = xmid
	} else {
		bit = 0
		this.low = xmid + 1
	}

	// Update predictor
	this.predictor.Update(byte(bit))

	// Read 32 bits from bitstream
	for (this.low^this.high)&0xFFFFFFFF000000 == 0 {
		if err := this.Read(); err != nil {
			return 0, err
		}
	}

	return bit, nil
}

func (this *BinaryEntropyDecoder) Read() error {
	this.low = this.low << 32
	this.high = (this.high << 32) | 0xFFFFFFFF
	read, err := this.bitstream.ReadBits(32)

	if err == nil {
		this.current = (this.current << 32) | read
	}

	return err
}

func (this *BinaryEntropyDecoder) Decode(block []byte) (int, error) {
	err := error(nil)

	// Deferred initialization: the bistream may not be ready at build time
	// Initialize 'current' with bytes read from the bitstream
	if this.Initialized() == false {
		if err = this.Initialize(); err != nil {
			return 0, err
		}
	}

	for i := range block {
		if block[i], err = this.decodeByte_(); err != nil {
			return i, err
		}
	}

	return len(block), err
}

func (this *BinaryEntropyDecoder) BitStream() kanzi.InputBitStream {
	return this.bitstream
}

func (this *BinaryEntropyDecoder) Dispose() {
}
