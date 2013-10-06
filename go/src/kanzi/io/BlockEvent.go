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

package io

const (
	EVT_BEFORE_TRANSFORM = 0
	EVT_AFTER_TRANSFORM  = 1
	EVT_BEFORE_ENTROPY   = 2
	EVT_AFTER_ENTROPY    = 3
)

type BlockEvent struct {
	EventType int
	BlockId   int
	BlockSize int
	Hash      uint32
	Hashing   bool
}

func NewBlockEvent(type_, blockId, blockSize int, hash uint32, hashing bool) (*BlockEvent, error) {
	this := new(BlockEvent)
	this.EventType = type_
	this.BlockId = blockId
	this.BlockSize = blockSize
	this.Hash = hash
	this.Hashing = hashing
	return this, nil
}

type BlockListener interface {
	ProcessEvent(evt *BlockEvent)
}
