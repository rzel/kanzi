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
	"fmt"
	"kanzi"
)

type DefaultOutputBitStream struct {
	closed   bool
	written  uint64
	position int  // index of current byte in buffer
	bitIndex uint // index of current bit to write
	os       kanzi.OutputStream
	buffer   []byte
}

func NewDefaultOutputBitStream(stream kanzi.OutputStream, bufferSize uint) (*DefaultOutputBitStream, error) {
	if stream == nil {
		return nil, errors.New("Invalid null output stream parameter")
	}

	if bufferSize < 1024 {
		return nil, errors.New("Invalid buffer size parameter (must be at least 1024 bytes)")
	}

	this := new(DefaultOutputBitStream)
	this.buffer = make([]byte, bufferSize)
	this.os = stream
	this.bitIndex = 7
	return this, nil
}

func (this *DefaultOutputBitStream) WriteBit(bit int) error {
	this.buffer[this.position] |= byte((bit & 1) << this.bitIndex)

	if this.bitIndex == 0 {
		this.bitIndex = 7
		this.position++

		if this.position >= len(this.buffer) {
			return this.Flush()
		}
	} else {
		this.bitIndex--
	}

	return nil
}

func (this *DefaultOutputBitStream) WriteBits(value uint64, length uint) (uint, error) {
	if length == 0 {
		return 0, nil
	}

	if length > 64 {
		return 0, fmt.Errorf("Invalid length: %v (must be in [1..64])", length)
	}

	remaining := length

	// Pad the current position in buffer
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
		bits := ((value >> remaining) & ((1 << sz) - 1))
		this.buffer[this.position] |= byte(bits << idx)
		this.bitIndex = (idx + 7) & 7

		if this.bitIndex == 7 {
			this.position++

			if this.position >= len(this.buffer) {
				if err := this.Flush(); err != nil {
					return length - remaining, err
				}
			}
		}
	}

	for remaining >= 8 {
		// Fast track, progress byte by byte
		remaining -= 8
		this.buffer[this.position] = byte(value >> remaining)
		this.position++

		if this.position >= len(this.buffer) {
			if err := this.Flush(); err != nil {
				return length - remaining, err
			}
		}
	}

	// Process remaining bits
	if remaining > 0 {
		this.bitIndex -= remaining
		this.buffer[this.position] |= byte(value << (8 - remaining))
	}

	return length, nil
}

func (this *DefaultOutputBitStream) Flush() error {
	if this.Closed() {
		return errors.New("Stream closed")
	}

	if this.position > 0 {
		// The buffer contains an incomplete byte at 'position'
		if _, err := this.os.Write(this.buffer[0:this.position]); err != nil {
			return err
		}

		this.written += uint64(this.position << 3)

		if this.bitIndex != 7 {
			this.buffer[0] = this.buffer[this.position]
		} else {
			this.buffer[0] = 0
		}

		end := this.position

		if this.position >= len(this.buffer) {
			end = len(this.buffer) - 1
		}

		for i := 1; i <= end; i++ {
			// do not reset buffer[0]
			this.buffer[i] = 0
		}

		this.position = 0
	}

	return this.os.Sync()
}

func (this *DefaultOutputBitStream) Close() (bool, error) {
	if this.Closed() {
		return true, nil
	}

	if this.bitIndex != 7 {
		// Ready to write the incomplete last byte
		this.position++
		this.bitIndex = 7
	}

	this.Flush()
	this.closed = true

	// Force an error on any subsequent write attempt
	this.position = len(this.buffer)
	this.bitIndex = 7
	return true, this.os.Close()
}

func (this *DefaultOutputBitStream) Written() uint64 {
	if this.closed {
		return this.written
	} else {
		// Number of bits flushed + bytes written in memory + bits written in memory
		return this.written + uint64(this.position<<3) + uint64(7-this.bitIndex)
	}
}

func (this *DefaultOutputBitStream) Closed() bool {
	return this.closed
}
