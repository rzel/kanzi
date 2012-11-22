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

package bitstream

import (
	"kanzi"
	"errors"
)

type DefaultInputBitStream struct {
	closed      bool
	read        uint64
	position    int
	maxPosition int
	bitIndex    uint
	is          kanzi.InputStream
	buffer      []byte
}

func NewDefaultInputBitStream(stream kanzi.InputStream) (*DefaultInputBitStream, error) {
	if stream == nil {
		return nil, errors.New("The stream cannot be null")
	}

	this := new(DefaultInputBitStream)
	this.buffer = make([]byte, 16384)
	this.is = stream
	this.bitIndex = 7
	this.position = -1
	this.maxPosition = -1
	return this, nil
}

func (this *DefaultInputBitStream) ReadBit() (int, error) {
	err := error(nil)

	if this.bitIndex == 7 {
		this.position++

		for this.position > this.maxPosition {
			_, err = ReadFromInputStream(this, len(this.buffer))

			if err != nil {
				return 0, err
			}

			this.position++
		}
	}

	bit := int((this.buffer[this.position] >> this.bitIndex) & 1)
	this.bitIndex = (this.bitIndex + 7) & 7
	this.read++
	return bit, err
}

func (this *DefaultInputBitStream) ReadBits(length uint) (uint64, error) {
	if length == 0 || length > 64 {
		return 0, errors.New("Length must be in the [1..64] range")
	}

	remaining := length
	err := error(nil)
	res := uint64(0)

	// Extract bits from the current location in buffer
	if this.bitIndex != 7 {
		idx := uint(this.bitIndex)
		sz := remaining

		if remaining > idx+1 {
			sz = idx + 1
		}

		remaining -= sz
		bits := uint64(this.buffer[this.position]>>(idx+1-sz)) & ((1 << sz) - 1)
		res |= (bits << remaining)
		idx = (idx + 8 - sz) & 7
		this.read += uint64(sz)
		this.bitIndex = idx
	}

	// Need to read more bits ?
	if this.bitIndex == 7 {

		// We are byte aligned, fast track
		for remaining >= 8 {
			this.position++

			for this.position > this.maxPosition {
				ReadFromInputStream(this, len(this.buffer))
				this.position++
			}

			value := uint64(this.buffer[this.position] & 0xFF)
			remaining -= 8
			this.read += 8
			res |= (value << remaining)
		}

		// Extract last bits from the current location in buffer
		if remaining > 0 {
			this.position++

			for this.position > this.maxPosition {
				ReadFromInputStream(this, len(this.buffer))
				this.position++
			}

			value := uint64(this.buffer[this.position] & 0xFF)
			bits := (value >> (8 - remaining)) & ((1 << remaining) - 1)
			res |= bits
			this.read += uint64(remaining)
			this.bitIndex -= remaining
		}
	}

	return res, err
}

func ReadFromInputStream(this *DefaultInputBitStream, length int) (int, error) {
	if this.Closed() {
		return 0, errors.New("Bit stream closed")
	}

	size, err := this.is.Read(this.buffer[0:length])

	if err == nil && size >= 0 {
		this.position = -1
		this.maxPosition = size - 1
	}

	return size, err
}

func (this *DefaultInputBitStream) HasMoreToRead() (bool, error) {
	if this.Closed() {
		return false, errors.New("Bit stream closed")
	}

	if this.position < this.maxPosition || this.bitIndex != 7 {
		return true, nil
	}

	_, err := ReadFromInputStream(this, len(this.buffer))
	return (err == nil), err
}

func (this *DefaultInputBitStream) Close() (bool, error) {
	if this.Closed() {
		return true, nil
	}

	// Reset fields to force a ReadFromInputStream() (that will report an error)
	// on readBit() or readBits()
	this.closed = true
	this.bitIndex = 7
	this.position = -1
	this.maxPosition = -1

	err := this.is.Close()
	return true, err
}

func (this *DefaultInputBitStream) Read() uint64 {
	return this.read
}

func (this *DefaultInputBitStream) Closed() bool {
	return this.closed
}
