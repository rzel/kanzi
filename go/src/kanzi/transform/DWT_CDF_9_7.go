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

package transform

import (
	"errors"
)

// Discrete Wavelet Transform Cohen-Daubechies-Fauveau 9/7 for 2D signals
const (
	SHIFT  = 12
	ADJUST = 1 << (SHIFT - 1)

	PREDICT_1 = 6497 // with SHIFT = 12
	UPDATE_1  = 217  // with SHIFT = 12
	PREDICT_2 = 3616 // with SHIFT = 12
	UPDATE_2  = 1817 // with SHIFT = 12
	SCALING_1 = 4709 // with SHIFT = 12
	SCALING_2 = 3562 // with SHIFT = 12
)

type DWT_CDF_9_7 struct {
	width  uint // at least 8
	height uint // at least 8
	steps  uint // at least 1
	// ensure (width >> steps) << steps == width
	// ensure (height >> steps) << steps == height
	data []int // width * height
}

func NewDWT(width, height, steps uint) (*DWT_CDF_9_7, error) {
	if width < 8 {
		return nil, errors.New("Invalid transform width (must be at least 8)")
	}

	if height < 8 {
		return nil, errors.New("Invalid transform height (must be at least 8)")
	}

	if steps < 1 {
		return nil, errors.New("Invalid number of iterations (must be at least 1)")
	}

	if (width>>steps)<<steps != width {
		return nil, errors.New("The width is not equal to 2^steps")
	}

	if (height>>steps)<<steps != height {
		return nil, errors.New("The height is not equal to 2^steps")
	}

	this := new(DWT_CDF_9_7)
	this.data = make([]int, width*height)
	return this, nil
}

func (this *DWT_CDF_9_7) Forward(block []int) []int {
	for i := uint(0); i < this.steps; i++ {
		// First, vertical transform
		block = this.ComputeForward(block, this.width, 1, this.width>>i, this.height>>i)

		// Then horizontal transform on the updated signal
		block = this.ComputeForward(block, 1, this.width, this.height>>i, this.width>>i)
	}

	return block
}

func (this *DWT_CDF_9_7) ComputeForward(block []int, stride, inc, dim1, dim2 uint) []int {
	stride2 := stride << 1
	endOffs := dim1 * inc
	half := stride * (dim2 >> 1)

	for offset := uint(0); offset < endOffs; offset += inc {
		end := offset + (dim2-2)*stride
		tmp := int64(0)
		prev := block[offset]

		// First lifting stage : Predict 1
		for i := offset + stride; i < end; i += stride2 {
			next := block[i+stride]
			tmp = int64(PREDICT_1 * (prev + next))
			block[i] -= int((tmp + ADJUST) >> SHIFT)
			prev = next
		}

		tmp = int64(PREDICT_1 * block[end])
		block[end+stride] -= int(((tmp + tmp) + ADJUST) >> SHIFT)
		prev = block[offset+stride]

		// Second lifting stage : Update 1
		for i := offset + stride2; i <= end; i += stride2 {
			next := block[i+stride]
			tmp = int64(UPDATE_1 * (prev + next))
			block[i] -= int((tmp + ADJUST) >> SHIFT)
			prev = next
		}

		tmp = int64(UPDATE_1 * block[offset+stride])
		block[offset] -= int(((tmp + tmp) + ADJUST) >> SHIFT)
		prev = block[offset]

		// Third lifting stage : Predict 2
		for i := offset + stride; i < end; i += stride2 {
			next := block[i+stride]
			tmp = int64(PREDICT_2 * (prev + next))
			block[i] += int((tmp + ADJUST) >> SHIFT)
			prev = next
		}

		tmp = int64(PREDICT_2 * block[end])
		block[end+stride] += int(((tmp + tmp) + ADJUST) >> SHIFT)
		prev = block[offset+stride]

		// Fourth lifting stage : Update 2
		for i := offset + stride2; i <= end; i += stride2 {
			next := block[i+stride]
			tmp = int64(UPDATE_2 * (prev + next))
			block[i] += int((tmp + ADJUST) >> SHIFT)
			prev = next
		}

		tmp = int64(UPDATE_2 * block[offset+stride])
		block[offset] += int(((tmp + tmp) + ADJUST) >> SHIFT)

		// Scale
		for i := offset; i <= end; i += stride2 {
			tmp = int64(block[i] * SCALING_1)
			block[i] = int((tmp + ADJUST) >> SHIFT)
			tmp = int64(block[i+stride] * SCALING_2)
			block[i+stride] = int((tmp + ADJUST) >> SHIFT)
		}

		// De-interleave sub-bands
		endj := offset + half
		i := offset

		for j := offset; j < endj; j += stride {
			this.data[j] = block[i]
			this.data[half+j] = block[i+stride]
			i += stride2
		}

		block[end+stride] = this.data[end+stride]

		for i := offset; i <= end; i += stride {
			block[i] = this.data[i]
		}
	}

	return block
}

func (this *DWT_CDF_9_7) Inverse(block []int) []int {
	for i := this.steps - 1; i >= 0; i-- {
		// First horizontal transform
		block = this.ComputeInverse(block, 1, this.width, this.height>>i, this.width>>i)

		// Then vertical transform on the updated signal
		block = this.ComputeInverse(block, this.width, 1, this.width>>i, this.height>>i)
	}

	return block
}

func (this *DWT_CDF_9_7) ComputeInverse(block []int, stride, inc, dim1, dim2 uint) []int {
	stride2 := stride << 1
	endOffs := dim1 * inc
	half := stride * (dim2 >> 1)

	for offset := uint(0); offset < endOffs; offset += inc {
		end := offset + (dim2-2)*stride
		endj := offset + half
		tmp := int64(0)

		// Interleave sub-bands
		for i := offset; i <= end; i += stride {
			this.data[i] = block[i]
		}

		this.data[end+stride] = block[end+stride]
		i := offset

		for j := offset; j < endj; j += stride {
			block[i] = this.data[j]
			block[i+stride] = this.data[half+j]
			i += stride2
		}

		// Reverse scale
		for i := offset; i <= end; i += stride2 {
			tmp = int64(block[i] * SCALING_2)
			block[i] = int((tmp + ADJUST) >> SHIFT)
			tmp = int64(block[i+stride] * SCALING_1)
			block[i+stride] = int((tmp + ADJUST) >> SHIFT)
		}

		// Reverse Update 2
		prev := block[offset+stride]

		for i := offset + stride2; i <= end; i += stride2 {
			next := block[i+stride]
			tmp = int64(UPDATE_2 * (prev + next))
			block[i] -= int((tmp + ADJUST) >> SHIFT)
			prev = next
		}

		tmp = int64(UPDATE_2 * block[offset+stride])
		block[offset] -= int(((tmp + tmp) + ADJUST) >> SHIFT)
		prev = block[offset]

		// Reverse Predict 2
		for i := offset + stride; i < end; i += stride2 {
			next := block[i+stride]
			tmp = int64(PREDICT_2 * (prev + next))
			block[i] -= int((tmp + ADJUST) >> SHIFT)
			prev = next
		}

		tmp = int64(PREDICT_2 * block[end])
		block[end+stride] -= int(((tmp + tmp) + ADJUST) >> SHIFT)
		prev = block[offset+stride]

		// Reverse Update 1
		for i := offset + stride2; i <= end; i += stride2 {
			next := block[i+stride]
			tmp = int64(UPDATE_1 * (prev + next))
			block[i] += int((tmp + ADJUST) >> SHIFT)
			prev = next
		}

		tmp = int64(UPDATE_1 * block[offset+stride])
		block[offset] += int(((tmp + tmp) + ADJUST) >> SHIFT)
		prev = block[offset]

		// Reverse Predict 1
		for i := offset + stride; i < end; i += stride2 {
			next := block[i+stride]
			tmp = int64(PREDICT_1 * (prev + next))
			block[i] += int((tmp + ADJUST) >> SHIFT)
			prev = next
		}

		tmp = int64(PREDICT_1 * block[end])
		block[end+stride] += int(((tmp + tmp) + ADJUST) >> SHIFT)
	}

	return block
}
