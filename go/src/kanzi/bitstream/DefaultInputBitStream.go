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

package bitstream

import (
	"errors"
	"kanzi"
)

type DefaultInputBitStream struct {
	closed      bool
	read        uint64
	position    int  // index of current byte (consumed if bitIndex == 7)
	bitIndex    uint // index of current bit to read
	is          kanzi.InputStream
	buffer      []byte
	maxPosition int
}

func NewDefaultInputBitStream(stream kanzi.InputStream, bufferSize uint) (*DefaultInputBitStream, error) {
	if stream == nil {
		return nil, errors.New("Invalid null input stream parameter")
	}

	if bufferSize < 1024 {
		return nil, errors.New("Invalid buffer size parameter (must be at least 1024 bytes)")
	}

	this := new(DefaultInputBitStream)
	this.buffer = make([]byte, bufferSize)
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

		if this.position > this.maxPosition {
			if _, err = ReadFromInputStream(this, len(this.buffer)); err != nil {
				return 0, err
			}
		}
	}

	bit := int(this.buffer[this.position]>>this.bitIndex) & 1
	this.bitIndex = (this.bitIndex + 7) & 7
	return bit, err
}

func (this *DefaultInputBitStream) ReadBits(length uint) (uint64, error) {
	if length == 0 || length > 64 {
		return 0, errors.New("Length must be in the [1..64] range")
	}

	remaining := length
	res := uint64(0)

	// Extract bits from the current location in buffer
	if this.bitIndex != 7 {
		idx := this.bitIndex + 1
		var sz uint

		if remaining > idx {
			sz = idx
		} else {
			sz = remaining
		}

		remaining -= sz
		idx -= sz
		bits := uint64(this.buffer[this.position]>>idx) & ((1 << sz) - 1)
		res |= (bits << remaining)
		this.bitIndex = (idx + 7) & 7
	}

	for remaining >= 8 {
		// Fast track, progress byte by byte
		this.position++

		if this.position > this.maxPosition {
			if _, err := ReadFromInputStream(this, len(this.buffer)); err != nil {
				return res, err
			}
		}

		remaining -= 8
		res |= (uint64(this.buffer[this.position]) << remaining)
	}

	// Extract last bits from the current location in buffer
	if remaining > 0 {
		this.position++

		if this.position > this.maxPosition {
			if _, err := ReadFromInputStream(this, len(this.buffer)); err != nil {
				return res, err
			}
		}

		this.bitIndex -= remaining
		res |= uint64(this.buffer[this.position] >> (8 - remaining))
	}

	return res, nil
}

func ReadFromInputStream(this *DefaultInputBitStream, length int) (int, error) {
	if this.Closed() {
		return 0, errors.New("Stream closed")
	}

	this.read += uint64((this.maxPosition + 1) << 3)
	size, err := this.is.Read(this.buffer[0:length])

	if err != nil {
		return size, err
	}

	if size <= 0 {
		return size, errors.New("No more data to read in the bitstream")
	}

	this.position = 0
	this.maxPosition = size - 1
	return size, nil
}

func (this *DefaultInputBitStream) HasMoreToRead() (bool, error) {
	if this.Closed() {
		return false, errors.New("Stream closed")
	}

	if this.position < this.maxPosition || this.bitIndex != 7 {
		return true, nil
	}

	_, err := ReadFromInputStream(this, len(this.buffer))
	return err == nil, err
}

func (this *DefaultInputBitStream) Close() (bool, error) {
	if this.Closed() {
		return true, nil
	}

	// Reset fields to force a ReadFromInputStream() and trigger an error
	// on readBit() or readBits()
	this.closed = true
	this.bitIndex = 7
	this.maxPosition = -1
	return true, this.is.Close()
}

func (this *DefaultInputBitStream) Read() uint64 {
	// Number of bits read from OS + bytes read in memory + bits read in memory
	// If this.bitIndex == 7, the byte at this.position has been completely consumed
	indexByte := uint64(this.position)

	if this.bitIndex == 7 {
		indexByte++
	}

	indexBit := uint64(7 - this.bitIndex)
	return this.read + (indexByte << 3) + indexBit
}

func (this *DefaultInputBitStream) Closed() bool {
	return this.closed
}
