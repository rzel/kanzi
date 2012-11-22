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

package entropy

import (
	"kanzi"
	"errors"
)

type Predictor interface {
	// Used to update the probability model
	Update(bit byte)

	// Return the split value representing the  probability for each symbol 
	// in the [0..4095] range. 
	// E.G. 410 represents roughly a probability of 10% for 0
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
	this.high = uint64(0xFFFFFFFF)
	this.bitstream = bs
	return this, nil
}

func (this *BinaryEntropyEncoder) EncodeByte(val byte) error {
	for i := 7; i >= 0; i-- {
		err := this.EncodeBit((val >> uint(i)) & 1)

		if err != nil {
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
	if (bit & 1) == 1 {
		this.high = xmid
	} else {
		this.low = xmid + 1
	}

	// Update predictor
	this.predictor.Update(bit)

	// Write unchanged first 8 bits to bitstream
	for ((this.low ^ this.high) & uint64(0xFF000000)) == 0 {
		this.Flush()
	}

	return nil
}

func (this *BinaryEntropyEncoder) Encode(block []byte) (int, error) {
	return EntropyEncodeArray(this, block)
}

func (this *BinaryEntropyEncoder) Flush() {
	this.bitstream.WriteBits(this.high>>24, 8)
	this.low <<= 8
	this.high = (this.high << 8) | uint64(255)
}

func (this *BinaryEntropyEncoder) BitStream() kanzi.OutputBitStream {
	return this.bitstream
}

func (this *BinaryEntropyEncoder) Dispose() {
	this.bitstream.WriteBits(this.low|uint64(0xFFFFFF), 32)
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

	this := new(BinaryEntropyDecoder)
	this.predictor = predictor
	this.low = 0
	this.high = uint64(0xFFFFFFFF)
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

	res := 0

	for i := 7; i >= 0; i-- {
		bit, err := this.DecodeBit()

		if err != nil {
			return 0, err
		}

		res |= bit << uint(i)
	}

	return byte(res), nil
}

func (this *BinaryEntropyDecoder) Initialized() bool {
	return this.initialized
}

func (this *BinaryEntropyDecoder) Initialize() error {
	if this.initialized == false {
		read, err := this.bitstream.ReadBits(32)

		if err != nil {
			return err
		}

		this.current = uint64(read)
		this.initialized = true
	}

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

	// Read from bitstream
	for ((this.low ^ this.high) & uint64(0xFF000000)) == 0 {
		err := this.Read()

		if err != nil {
			return 0, err
		}
	}

	return bit, nil
}

func (this *BinaryEntropyDecoder) Read() error {
	this.low = uint64(this.low << 8)
	this.high = uint64((this.high << 8) | 255)
	read, err := this.bitstream.ReadBits(8)

	if err != nil {
		return err
	}

	this.current = uint64((this.current << 8) | read)
	return nil
}

func (this *BinaryEntropyDecoder) Decode(block []byte) (int, error) {
	return EntropyDecodeArray(this, block)
}

func (this *BinaryEntropyDecoder) BitStream() kanzi.InputBitStream {
	return this.bitstream
}

func (this *BinaryEntropyDecoder) Dispose() {
}
