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
	"errors"
)

type ExpGolombEncoder struct {
	signed    bool
	bitstream kanzi.OutputBitStream
}

// If sgn is true, the input value is turned into an int8
// Managing sign improves compression ratio for distributions centered on 0 (E.G. Gaussian)
// Example: -1 is better compressed as int8 (1 followed by -) than as byte (-1 & 255 = 255)
func NewExpGolombEncoder(bs kanzi.OutputBitStream, sgn bool) (*ExpGolombEncoder, error) {
	if bs == nil {
		return nil, errors.New("Bit stream parameter cannot be null")
	}

	this := new(ExpGolombEncoder)
	this.signed = sgn
	this.bitstream = bs
	return this, nil
}

func (this *ExpGolombEncoder) Signed() bool {
	return this.signed
}

func (this *ExpGolombEncoder) Dispose() {
}

func (this *ExpGolombEncoder) EncodeByte(val byte) error {
	if val == 0 {
		return this.bitstream.WriteBit(1)
	}

	var val2 byte

	if this.signed == true {
		sVal := int8(val)
		//  Take the abs() of 'sVal' 
		val2 = byte((sVal + (sVal >> 7)) ^ (sVal >> 7))
	} else {
		val2 = val
	}

	val2++
	emit := uint64(val2)
	var n uint

	if val2 <= 3 {
		// shortcut when abs(input) = 1 or 2
		n = 3
	} else {
		//  Count the bits (log2), subtract one, and write that number of zeros
		//  preceding the previous bit string to get the encoded value
		log2 := uint(0)

		for val2 > 1 {
			log2++
			val2 >>= 1
		}

		// Add log2 zeros and 1 one (unary coding), then remainder
		// 0 => 1 => 1
		// 1 => 10 => 010
		// 2 => 11 => 011
		// 3 => 100 => 00100
		// 4 => 101 => 00101
		// 5 => 110 => 00110
		// 6 => 111 => 00111
		n = log2 + (log2 + 1)
	}

	if this.signed == true {
		// Add 0 for positive and 1 for negative sign (considering
		// msb as byte 'sign')
		n++
		emit = (emit << 1) | uint64((val>>7)&1)
	}

	_, err := this.bitstream.WriteBits(emit, n)
	return err
}

func (this *ExpGolombEncoder) BitStream() kanzi.OutputBitStream {
	return this.bitstream
}

func (this *ExpGolombEncoder) Encode(block []byte) (int, error) {
	return EntropyEncodeArray(this, block)
}

type ExpGolombDecoder struct {
	signed    bool
	bitstream kanzi.InputBitStream
}

// If sgn is true, the extracted value is treated as an int8
func NewExpGolombDecoder(bs kanzi.InputBitStream, sgn bool) (*ExpGolombDecoder, error) {
	if bs == nil {
		return nil, errors.New("Bit stream parameter cannot be null")
	}

	this := new(ExpGolombDecoder)
	this.signed = sgn
	this.bitstream = bs
	return this, nil
}

func (this *ExpGolombDecoder) Signed() bool {
	return this.signed
}

func (this *ExpGolombDecoder) Dispose() {
}

// If the decoder is signed, the returned value is a byte encoded int8
func (this *ExpGolombDecoder) DecodeByte() (byte, error) {
	// Decode unsigned
	var log2 uint

	for log2 = 0; log2 < 8; log2++ {
		r, err := this.bitstream.ReadBit()

		if err != nil {
			return 0, err
		}

		if r == 1 {
			break
		}
	}

	info := uint64(0)

	if log2 > 0 {
		val, err := this.bitstream.ReadBits(log2)

		if err != nil {
			return 0, err
		}

		info = val
	}

	res := byte((1 << log2) - 1 + info)

	// Read sign if necessary
	if res != 0 && this.signed == true {
		// If res != 0, Get the 'sign', encoded as 1 for 'negative values'
		sgn, err := this.bitstream.ReadBit()

		if err != nil {
			return res, err
		}

		if sgn == 1 {
			sVal := int8(-res)
			return byte(sVal), nil
		}
	}

	return res, nil
}

func (this *ExpGolombDecoder) BitStream() kanzi.InputBitStream {
	return this.bitstream
}

func (this *ExpGolombDecoder) Decode(block []byte) (int, error) {
	return EntropyDecodeArray(this, block)
}
