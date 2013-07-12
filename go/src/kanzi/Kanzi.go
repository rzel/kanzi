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

package kanzi

type IntTransform interface {
	Forward(block []int) []int

	Inverse(block []int) []int
}

type ByteTransform interface {
	Forward(block []byte) []byte

	Inverse(block []byte) []byte
}

// Return index in src, index in dst and error
type IntFunction interface {
	Forward(src, dst []int) (uint, uint, error)

	Inverse(src, dst []int) (uint, uint, error)
}

// Return index in src, index in dst and error
type ByteFunction interface {
	Forward(src, dst []byte) (uint, uint, error)

	Inverse(src, dst []byte) (uint, uint, error)
}

type InputStream interface {
	Read(b []byte) (n int, err error)

	Close() error
}

type InputBitStream interface {
	ReadBit() (int, error)

	ReadBits(length uint) (uint64, error)

	Close() (bool, error)

	Read() uint64

	HasMoreToRead() (bool, error)
}

type OutputStream interface {
	Write(b []byte) (n int, err error)

	Close() error

	Sync() error
}

type OutputBitStream interface {
	WriteBit(bit int) error

	WriteBits(bits uint64, length uint) (uint, error)

	Flush() error

	Close() (bool, error)

	Written() uint64
}

type EntropyEncoder interface {
	// Encode the array provided into the bitstream. Return the number of byte
	// written to the bitstream
	Encode(block []byte) (int, error)

	// Encode the byte value provided into the bitstream
	EncodeByte(val byte) error

	// Return the underlying bitstream
	BitStream() OutputBitStream

	// Must be called before getting rid of the entropy encoder
	Dispose()
}

type EntropyDecoder interface {
	// Decode the next chunk of data from the bitstream and return as a byte
	DecodeByte() (byte, error)

	// Decode the next chunk of data from the bitstream and return in the 
	// provided buffer.
	Decode(block []byte) (int, error)

	// Return the underlying bitstream
	BitStream() InputBitStream

	// Must be called before getting rid of the entropy decoder
	// Trying to encode after a call to dispose gives undefined behavior
	Dispose()
}

