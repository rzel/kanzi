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

package function

// Zero Length Encoding is a simple encoding algorithm by Wheeler
// closely related to Run Length Encoding. The main difference is
// that only runs of 0 values are processed. Also, the length is
// encoded in a different way (each digit in a different byte)
// This algorithm is well adapted to process post BWT/MTFT data

const (
	ZLT_MAX_RUN = uint(1 << 31)
)

type ZLT struct {
	size   uint
	copies uint
}

func NewZLT(sz uint) (*ZLT, error) {
	this := new(ZLT)
	this.size = sz
	return this, nil
}

func (this *ZLT) Size() uint {
	return this.size
}

func (this *ZLT) Forward(src, dst []byte) (uint, uint, error) {
	srcEnd := this.size

	if this.size == 0 {
		srcEnd = uint(len(src))
	}

	runLength := this.copies
	srcIdx := uint(0)
	dstIdx := uint(0)

	for srcIdx < srcEnd && dstIdx < uint(len(dst)) {
		val := src[srcIdx]

		if val == 0 {
			runLength++
			srcIdx++

			if srcIdx < srcEnd && runLength < ZLT_MAX_RUN {
				continue
			}
		}

		if runLength > 0 {
			// Write length
			log2 := uint(0)
			run := runLength + 1

			for val2 := run; val2 > 1; val2 >>= 1 {
				log2++
			}

			if dstIdx <= uint(len(dst))-log2 {
				// Write every bit as a byte except the most significant one
				for log2 > 0 {
					log2--
					dst[dstIdx] = byte((run >> log2) & 1)
					dstIdx++
				}

				runLength = 0
				continue
			} else {
				// Will reach end of destination array, must truncate block
				// Can only write the bits that fit into the destination array
				log2 = uint(len(dst)) - dstIdx

				// Write every bit as a byte except the most significant one
				for dstIdx < uint(len(dst)) {
					dst[dstIdx] = byte(1)
					dstIdx++
				}

				// The most significant bit is not encoded, so log2 corresponds
				// to the max value of (1 << ((log2+1) + 1)) - 1
				delta := uint((1 << (log2 + 2)) - 1)
				runLength -= delta
				srcIdx -= delta
				break
			}
		}

		if val >= 0xFE {
			if dstIdx >= uint(len(dst)-1) {
				break
			}

			dst[dstIdx] = byte(0xFF)
			dstIdx++
			dst[dstIdx] = byte(val - 0xFE)
			dstIdx++
		} else {
			dst[dstIdx] = byte(val + 1)
			dstIdx++
		}

		srcIdx++
	}

	this.copies = runLength
	return srcIdx, dstIdx, nil
}

func (this *ZLT) Inverse(src, dst []byte) (uint, uint, error) {
	srcEnd := this.size

	if this.size == 0 {
		srcEnd = uint(len(src))
	}

	runLength := this.copies
	srcIdx := uint(0)
	dstIdx := uint(0)

	for srcIdx < srcEnd && dstIdx < uint(len(dst)) {
		if runLength > 0 {
			runLength--
			dst[dstIdx] = 0
			dstIdx++
			continue
		}

		val := uint(src[srcIdx])

		if val <= 1 {
			// Generate the run length bit by bit (but force MSB)
			run := uint(1)

			// Exit if no more data to read from source array (incomplete length)
			// Calling the method again with reset arrays will resume 'correctly'
			for val <= 1 {
				run = (run << 1) | val
				srcIdx++

				if srcIdx >= srcEnd {
					break
				}

				val = uint(src[srcIdx])
			}

			// Update run length
			runLength = uint(run - 1)
			continue
		}

		// Regular data processing
		if val > 0xFE {
			srcIdx++
			val += uint(src[srcIdx] & 0x01)
		}

		dst[dstIdx] = byte(val - 1)
		dstIdx++
		srcIdx++
	}

	min := int(runLength)

	if runLength > uint(len(dst))-dstIdx {
		min = len(dst) - int(dstIdx)
	}

	min--

	for min >= 0 {
		dst[dstIdx] = 0
		dstIdx++
		min--
	}

	this.copies = runLength
	return srcIdx, dstIdx, nil
}
