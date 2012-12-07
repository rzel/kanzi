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

package util

// MurmurHash3 was written by Austin Appleby, and is placed in the public
// domain. The author hereby disclaims copyright to this source code.
// Original source code: http://code.google.com/p/smhasher/


const (
	C1 = uint(0xcc9e2d51)
	C2 = uint(0x1b873593)
	C3 = uint(0xe6546b64)
	C4 = uint(0x85ebca6b)
	C5 = uint(0xc2b2ae35)
)

type MurMurHash3 struct {
	seed uint
}

func NewMurMurHash3(seed uint) (*MurMurHash3, error) {
	this := new(MurMurHash3)
	this.seed = seed
	return this, nil
}

func (this *MurMurHash3) SetSeed(seed uint) {
	this.seed = seed
}

func (this *MurMurHash3) Hash(data []byte) uint {
	h1 := this.seed // aliasing
	end4 := (len(data) & -3)

	// Body
	for i := 0; i < end4; i += 4 {
		k1 := uint(data[i]) | (uint(data[i+1]) << 8) |
			(uint(data[i+2]) << 16) | (uint(data[i+3]) << 24)

		k1 *= C1
		k1 = (k1 << 15) | (k1 >> 17)
		k1 *= C2
		h1 ^= k1
		h1 = (h1 << 13) | (h1 >> 19)
		h1 = (h1 * 5) + C3
	}

	// Tail
	k1 := uint(0)
	shift := uint(0)

	for i := end4; i < len(data); i++ {
		k1 ^= uint(data[i]) << shift
		shift += 8
	}

	k1 *= C1
	k1 = (k1 << 15) | (k1 >> 17)
	k1 *= C2
	h1 ^= k1

	// Finalization
	h1 ^= uint(len(data))
	h1 ^= (h1 >> 16)
	h1 *= C4
	h1 ^= (h1 >> 13)
	h1 *= C5
	return h1 ^ (h1 >> 16)
}
