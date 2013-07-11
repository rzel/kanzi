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

package util

import (
	"errors"
)

type ByteArrayOutputStream struct {
	array []byte
	index int
}

func NewByteArrayOutputStream(buffer []byte) (*ByteArrayOutputStream, error) {
	this := new(ByteArrayOutputStream)
	this.array = buffer
	return this, nil
}

func (this ByteArrayOutputStream) Write(b []byte) (n int, err error) {
	if len(b) > len(this.array)+this.index {
		return 0, errors.New("Output buffer too small")
	}

	copy(this.array[this.index:], b)
	this.index += len(b)
	return len(b), nil
}

func (this ByteArrayOutputStream) Close() error {
	return nil
}

func (this ByteArrayOutputStream) Sync() error {
	return nil
}

type ByteArrayInputStream struct {
	array []byte
	index int
}

func NewByteArrayInputStream(buffer []byte) (*ByteArrayInputStream, error) {
	this := new(ByteArrayInputStream)
	this.array = buffer
	return this, nil
}

func (this ByteArrayInputStream) Read(b []byte) (n int, err error) {
	if len(b) > len(this.array)+this.index {
		return 0, errors.New("Input buffer too small")
	}

	copy(b, this.array[this.index:])
	this.index += len(b)
	return len(b), nil
}

func (this ByteArrayInputStream) Close() error {
	return nil
}
