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

import (
	"errors"
)

type NullFunction struct {
	size uint
}

func NewNullFunction(sz uint) (*NullFunction, error) {
	this := new(NullFunction)
	this.size = sz
	return this, nil
}

func (this *NullFunction) Size() uint {
	return this.size
}

func doCopy(src, dst []byte, sz uint) (uint, uint, error) {
	length := sz

	if sz == 0 {
		length = uint(len(src))
	}

	if length > uint(len(dst)) {
		return uint(0), uint(0), errors.New("Destination buffer too small")
	}

	copy(dst, src[0:length])
	return length, length, nil
}

func (this *NullFunction) Forward(src, dst []byte) (uint, uint, error) {
	return doCopy(src, dst, this.size)
}

func (this *NullFunction) Inverse(src, dst []byte) (uint, uint, error) {
	return doCopy(src, dst, this.size)
}
