/*
Copyright 2011-2013 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License")
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

import (
	"fmt"
	"unsafe"
)

// Go implementation of a LZ4 codec.
// LZ4 is a very fast lossless compression algorithm created by Yann Collet.
// See original code here: https://code.google.com/p/lz4/
// More details on the algorithm are available here:
// http://fastcompression.blogspot.com/2011/05/lz4-explained.html

const (
	HASH_SEED                   = 0x9E3779B1
	HASH_LOG                    = 12
	HASH_LOG_64K                = 13
	MASK_HASH                   = -1
	MASK_HASH_64K               = 0xFFFF
	MAX_DISTANCE                = 1 << 16
	MAX_DISTANCE_64K            = 0x7FFFFFFF
	SKIP_STRENGTH               = 6
	LAST_LITERALS               = 5
	MIN_MATCH                   = 4
	MF_LIMIT                    = 12
	LZ4_64K_LIMIT               = (1 << 16) + (MF_LIMIT - 1)
	ML_BITS                     = 4
	ML_MASK                     = (1 << ML_BITS) - 1
	RUN_BITS                    = 8 - ML_BITS
	RUN_MASK                    = (1 << RUN_BITS) - 1
	COPY_LENGTH                 = 8
	MIN_LENGTH                  = 14
	DEFAULT_FIND_MATCH_ATTEMPTS = (1 << SKIP_STRENGTH) + 3
)

var (
	SHIFT1 = getShiftValue(0)
	SHIFT2 = getShiftValue(1)
	SHIFT3 = getShiftValue(2)
	SHIFT4 = getShiftValue(3)
)

func isBigEndian() bool {
	x := uint32(0x01020304)

	if *(*byte)(unsafe.Pointer(&x)) == 0x01 {
		return true
	}

	return false
}

func getShiftValue(index uint) uint {
	index &= 3

	if isBigEndian() {
		return 24 - (index << 3)
	}

	return index << 3
}

type LZ4Codec struct {
	size   uint
	buffer []int
}

func NewLZ4Codec(sz uint) (*LZ4Codec, error) {
	this := new(LZ4Codec)
	this.size = sz
	this.buffer = make([]int, 1<<HASH_LOG_64K)
	return this, nil
}

func (this *LZ4Codec) Size() uint {
	return this.size
}

func (this *LZ4Codec) SetSize(sz uint) bool {
	this.size = sz
	return true
}

func writeLength(array []byte, length int) int {
	index := 0

	for length >= 0xFF {
		array[index] = 0xFF
		length -= 0xFF
		index++
	}

	array[index] = byte(length)
	return index + 1
}

func emitLiterals(src []byte, dst []byte, runLen int, last bool) (int, int, int) {
	var token int
	dstIdx := 0

	// Emit literal lengths
	if runLen >= RUN_MASK {
		token = RUN_MASK << ML_BITS

		if last == true {
			dst[dstIdx] = byte(token)
			dstIdx++
		}

		dstIdx += writeLength(dst[dstIdx:], runLen-RUN_MASK)
	} else {
		token = runLen << ML_BITS

		if last == true {
			dst[dstIdx] = byte(token)
			dstIdx++
		}
	}

	// Emit literals
	for i := 0; i < runLen; i++ {
		dst[dstIdx+i] = src[i]
	}

	return runLen, dstIdx + runLen, token
}

func (this *LZ4Codec) Forward(src, dst []byte) (uint, uint, error) {
	count := this.size

	if this.size == 0 {
		count = uint(len(src))
	}

	if n := MaxEncodedLen(int(count)); len(dst) < n {
		return 0, 0, fmt.Errorf("Output buffer is too small - size: %d, required %d", len(dst), n)
	}

	if count < LZ4_64K_LIMIT {
		return this.doForward(src, dst, 0, HASH_LOG_64K, MASK_HASH_64K, MAX_DISTANCE_64K)
	} else {
		return this.doForward(src, dst, 0, HASH_LOG, MASK_HASH, MAX_DISTANCE)
	}
}

func (this *LZ4Codec) doForward(src []byte, dst []byte,
	base int, hashLog uint, hashMask int, dist int) (uint, uint, error) {
	count := this.size

	if this.size == 0 {
		count = uint(len(src))
	}

	if count < MIN_LENGTH {
		srcIdx, dstIdx, _ := emitLiterals(src, dst, int(count), true)
		return uint(srcIdx), uint(dstIdx), error(nil)
	}

	hashShift := 32 - hashLog
	srcEnd := int(count)
	srcLimit := srcEnd - LAST_LITERALS
	mfLimit := srcEnd - MF_LIMIT
	srcIdx := 0
	dstIdx := 0
	anchor := srcIdx
	srcIdx++
	table := this.buffer // aliasing

	for i := (1 << hashLog) - 1; i >= 0; i-- {
		table[i] = 0
	}

	for {
		attempts := DEFAULT_FIND_MATCH_ATTEMPTS
		var ref int

		// Find a match
		for {
			val32 := uint32(src[srcIdx]) << SHIFT1
			val32 |= (uint32(src[srcIdx+1]) << SHIFT2)
			val32 |= (uint32(src[srcIdx+2]) << SHIFT3)
			val32 |= (uint32(src[srcIdx+3]) << SHIFT4)
			h32 := (val32 * HASH_SEED) >> hashShift
			ref = base + (table[h32] & hashMask)
			table[h32] = srcIdx - base

			if (ref >= srcIdx-dist) && (src[ref] == src[srcIdx]) &&
				(src[ref+1] == src[srcIdx+1]) && (src[ref+2] == src[srcIdx+2]) &&
				(src[ref+3] == src[srcIdx+3]) {
				break
			}

			srcIdx += (attempts >> SKIP_STRENGTH)

			if srcIdx > mfLimit {
				_, dstDelta, _ := emitLiterals(src[anchor:], dst[dstIdx:], srcEnd-anchor, true)
				return uint(srcEnd), uint(dstIdx + dstDelta), error(nil)
			}

			attempts++
		}

		// Catch up
		for (ref > 0) && (srcIdx > anchor) && (src[ref-1] == src[srcIdx-1]) {
			ref--
			srcIdx--
		}

		// Encode literal length
		runLen := srcIdx - anchor
		tokenOff := dstIdx
		dstIdx++
		_, dstDelta, token := emitLiterals(src[anchor:], dst[dstIdx:], runLen, false)
		dstIdx += dstDelta

		for true {
			// Encode offset
			dst[dstIdx] = byte(srcIdx - ref)
			dstIdx++
			dst[dstIdx] = byte((srcIdx - ref) >> 8)
			dstIdx++

			// Count matches
			srcIdx += MIN_MATCH
			matchLen := 0
			idx1 := srcIdx
			idx2 := ref + MIN_MATCH

			for (idx1 < srcLimit) && (src[idx2] == src[idx1]) {
				idx1++
				idx2++
				matchLen++
			}

			srcIdx += matchLen

			// Encode match length
			if matchLen >= ML_MASK {
				dst[tokenOff] = byte(token | ML_MASK)
				dstIdx += writeLength(dst[dstIdx:], matchLen-ML_MASK)
			} else {
				dst[tokenOff] = byte(token | matchLen)
			}

			// Test end of chunk
			if srcIdx > mfLimit {
				_, dstDelta, _ := emitLiterals(src[srcIdx:], dst[dstIdx:], srcEnd-srcIdx, true)
				return uint(srcEnd), uint(dstIdx + dstDelta), error(nil)
			}

			// Test next position
			val32_1 := uint32(src[srcIdx-2]) << SHIFT1
			val32_1 |= (uint32(src[srcIdx-1]) << SHIFT2)
			val32_1 |= (uint32(src[srcIdx]) << SHIFT3)
			val32_1 |= (uint32(src[srcIdx+1]) << SHIFT4)
			h32_1 := (val32_1 * HASH_SEED) >> hashShift

			val32_2 := uint32(src[srcIdx]) << SHIFT1
			val32_2 |= (uint32(src[srcIdx+1]) << SHIFT2)
			val32_2 |= (uint32(src[srcIdx+2]) << SHIFT3)
			val32_2 |= (uint32(src[srcIdx+3]) << SHIFT4)
			h32_2 := (val32_2 * HASH_SEED) >> hashShift

			table[h32_1] = srcIdx - 2 - base
			ref = base + (table[h32_2] & hashMask)
			table[h32_2] = srcIdx - base

			if (ref <= srcIdx-dist) || (src[ref] != src[srcIdx]) ||
				(src[ref+1] != src[srcIdx+1]) || (src[ref+2] != src[srcIdx+2]) ||
				(src[ref+3] != src[srcIdx+3]) {
				break
			}

			tokenOff = dstIdx
			dstIdx++
			token = 0
		}

		// Update
		anchor = srcIdx
		srcIdx++
	}

	return uint(srcIdx), uint(dstIdx), error(nil)
}

func (this *LZ4Codec) Inverse(src, dst []byte) (uint, uint, error) {
	count := this.size

	if this.size == 0 {
		count = uint(len(src))
	}

	srcEnd := int(count)
	dstEnd := len(dst)
	srcEnd2 := srcEnd - COPY_LENGTH
	dstEnd2 := dstEnd - COPY_LENGTH
	srcIdx := 0
	dstIdx := 0

	for srcIdx < srcEnd {
		token := int(src[srcIdx] & 0xFF)
		srcIdx++

		// Literals
		length := token >> ML_BITS

		if length == RUN_MASK {
			for src[srcIdx] == byte(0xFF) {
				srcIdx++
				length += 0xFF
			}

			length += int(src[srcIdx] & 0xFF)
			srcIdx++
		}

		for i := 0; i < length; i++ {
			dst[dstIdx+i] = src[srcIdx+i]
		}

		srcIdx += length
		dstIdx += length

		if (dstIdx > dstEnd2) || (srcIdx > srcEnd2) {
			break
		}

		// Matches
		delta := int(src[srcIdx]) | (int(src[srcIdx+1]) << 8)
		srcIdx += 2
		matchOffset := dstIdx - delta
		length = token & ML_MASK

		if length == ML_MASK {
			for src[srcIdx] == byte(0xFF) {
				srcIdx++
				length += 0xFF
			}

			length += int(src[srcIdx] & 0xFF)
			srcIdx++
		}

		length += MIN_MATCH

		// Do not use copy on (potentially) overlapping slices
		for i := 0; i < length; i++ {
			dst[dstIdx+i] = dst[matchOffset+i]
		}

		dstIdx += length
	}

	return count, uint(dstIdx), nil
}

func MaxEncodedLen(srcLen int) int {
	return srcLen + (srcLen / 255) + 16
}
