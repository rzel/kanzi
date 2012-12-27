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

package entropy

import (
	"container/list"
	"errors"
	"kanzi"
	"sort"
)

const (
	DECODING_BATCH_SIZE = 10 // in bits
)

// ---- Utilities

// Huffman node
type HuffmanNode struct {
	symbol byte
	weight uint
	left   *HuffmanNode
	right  *HuffmanNode
}

type HuffmanCacheData struct {
	value *HuffmanNode
	next  *HuffmanCacheData
}

type HuffmanNodeArray []*HuffmanNode

func (array HuffmanNodeArray) Less(i, j int) bool {
	// Check sizes (reverse order) as first key
	if array[i].weight != array[j].weight {
		return array[j].weight < array[i].weight
	}

	// Check value (natural order) as second key
	return array[i].symbol < array[j].symbol
}

func (array HuffmanNodeArray) Len() int {
	return len(array)
}

func (array HuffmanNodeArray) Swap(i, j int) {
	array[i], array[j] = array[j], array[i]
}

type HuffmanNodeArray2 []*HuffmanNode

func (array HuffmanNodeArray2) Less(i, j int) bool {
	// Check sizes (natural order) as first key
	if array[i].weight != array[j].weight {
		return array[j].weight > array[i].weight
	}

	// Check value (natural order) as second key
	return array[i].symbol < array[j].symbol
}

func (array HuffmanNodeArray2) Len() int {
	return len(array)
}

func (array HuffmanNodeArray2) Swap(i, j int) {
	array[i], array[j] = array[j], array[i]
}

type HuffmanTree struct {
	codes         []uint
	sizes         []uint
	root          *HuffmanNode
	decodingCache []*HuffmanCacheData
	current       *HuffmanCacheData
}

// Used by encoder
func newHuffmanTreeFromFrequencies(frequencies []uint) (*HuffmanTree, error) {
	this := new(HuffmanTree)
	this.sizes = make([]uint, 256)

	// Create tree from frequencies
	this.root = this.createTreeFromFrequencies(frequencies)

	// Create canonical codes
	this.codes = generateCanonicalCodes(this.sizes)
	return this, nil
}

// Used by decoder
func newHuffmanTreeFromSizes(sz []uint, maxSize uint) (*HuffmanTree, error) {
	this := new(HuffmanTree)
	this.sizes = make([]uint, 256)
	copy(this.sizes, sz)

	// Create canonical codes
	this.codes = generateCanonicalCodes(this.sizes)

	// Create tree from code sizes
	this.root = createTreeFromSizes(maxSize, this.sizes, this.codes)
	this.decodingCache = createDecodingCache(this.root)
	this.current = &HuffmanCacheData{value: this.root} // point to root
	return this, nil
}

func createDecodingCache(rootNode *HuffmanNode) []*HuffmanCacheData {
	nodes := list.New()
	end := 1 << DECODING_BATCH_SIZE
	var previousData *HuffmanCacheData

	// Create an array storing a list of Nodes for each input byte value
	for val := 0; val < end; val++ {
		shift := DECODING_BATCH_SIZE - 1
		firstAdded := false

		for shift >= 0 {
			// Start from root
			currentNode := rootNode
			
			// Process next bit
			for shift >= 0 && (currentNode.left != nil || currentNode.right != nil) {

				if (val>>uint(shift))&1 == 0 {
					currentNode = currentNode.left
				} else {
					currentNode = currentNode.right
				}

				shift--
			}

			currentData := &HuffmanCacheData{value: currentNode}

			// The list is made of linked nodes
			if previousData != nil {
				previousData.next = currentData
			}

			previousData = currentData

			if firstAdded == false {
				// Add first node of list to array (whether it is a leaf or not)
				nodes.PushBack(currentData)
				firstAdded = true
			}
		}

		previousData.next = &HuffmanCacheData{value: rootNode}
		previousData = previousData.next
	}

	res := make([]*HuffmanCacheData, nodes.Len())
	n := 0

	for e := nodes.Front(); e != nil; e = e.Next() {
		res[n] = e.Value.(*HuffmanCacheData)
		n++
	}

	return res
}

func (this *HuffmanTree) createTreeFromFrequencies(frequencies []uint) *HuffmanNode {
	array := make(HuffmanNodeArray2, 256)
	n := 0

	for i := range array {
		if frequencies[i] > 0 {
			array[n] = &HuffmanNode{symbol: byte(i), weight: frequencies[i]}
			n++
		}
	}

	// Sort by frequency
	sort.Sort(array[0:n])

	// Create Huffman tree of (present) symbols
	queue1 := list.New()
	queue2 := list.New()
	nodes := make([]*HuffmanNode, 2)

	for i := n - 1; i >= 0; i-- {
			queue1.PushFront(array[i])
	}

	for queue1.Len()+queue2.Len() > 1 {
		// Extract 2 minimum nodes
		for i := range nodes {
			if queue1.Len() == 0 {
				nodes[i] = queue2.Front().Value.(*HuffmanNode)
				queue2.Remove(queue2.Front())
				continue
			}

			if queue2.Len() == 0 {
				nodes[i] = queue1.Front().Value.(*HuffmanNode)
				queue1.Remove(queue1.Front())
				continue
			}

			if queue1.Front().Value.(*HuffmanNode).weight <= queue2.Front().Value.(*HuffmanNode).weight {
				nodes[i] = queue1.Front().Value.(*HuffmanNode)
				queue1.Remove(queue1.Front())
			} else {
				nodes[i] = queue2.Front().Value.(*HuffmanNode)
				queue2.Remove(queue2.Front())
			}
		}

		// Merge minimum nodes and enqueue result
		lNode := nodes[0]
		rNode := nodes[1]
		merged := &HuffmanNode{weight: lNode.weight + rNode.weight, left: lNode, right: rNode}
		queue2.PushBack(merged)
	}

    var rootNode *HuffmanNode
    
	if queue1.Len() == 0 {
		rootNode = queue2.Front().Value.(*HuffmanNode)
	} else {
 		rootNode = queue1.Front().Value.(*HuffmanNode)
 	}
 	
	this.fillTree(rootNode, 0)
	return rootNode
}

// Fill size and code arrays
func (this *HuffmanTree) fillTree(node *HuffmanNode, depth uint) {
	if node.left == nil && node.right == nil {
		idx := node.symbol
		this.sizes[idx] = depth	
		return
	}

	if node.left != nil {
		this.fillTree(node.left, depth+1)
	}

	if node.right != nil {
		this.fillTree(node.right, depth+1)
	}
}

func createTreeFromSizes(maxSize uint, lengths []uint, codes []uint) *HuffmanNode {
	codeMap := make(map[int]*HuffmanNode)
	sum := uint(1 << maxSize)
	keys := make([]int, len(lengths))
	tree := kanzi.IntBTree{} //use binary tree
	codeMap[0] = &HuffmanNode{symbol: byte(0), weight: sum}
	tree.Add(0) // add key(0,0)

	// Create node for each (present) symbol and add to map
	for i := range lengths {
		if lengths[i] == 0 {
			continue
		}

		key := int((lengths[i] << 16) | codes[i])
		tree.Add(key)
		value := &HuffmanNode{symbol: byte(i), weight: sum >> lengths[i]}
		codeMap[key] = value
	}

	// Process each element of the map except the root node
	for tree.Size() > 1 {
		key, _ := tree.Max()
		tree.Remove(key)
		node := codeMap[key]
		l := (key >> 16) & 0xFFFF
		c := key & 0xFFFF
		upKey := ((l - 1) << 16) | ((c >> 1) & 0xFF)
		upNode := codeMap[upKey]

		// Create superior node if it does not exist (length gap > 1)
		if upNode == nil {
			upNode = &HuffmanNode{symbol: byte(0), weight: sum >> uint(l-1)}
			codeMap[upKey] = upNode
			tree.Add(upKey)
		}

		// Add the current node to its parent at the correct place
		if c&1 == 1 {
			upNode.right = node
		} else {
			upNode.left = node
		}
	}

	// Return the last element of the map (root node)
	return codeMap[keys[0]]
}

func generateCanonicalCodes(lengths []uint) []uint {
	array := make(HuffmanNodeArray, len(lengths))
	n := 0

	for i := range array {
		if lengths[i] > 0 {
			array[n] = &HuffmanNode{symbol: byte(i), weight: lengths[i]}
			n++
		}
	}

	// Sort by decreasing size and increasing value
	sort.Sort(array[0:n])
	codes := make([]uint, 256)
	code := uint(0)
	length := lengths[array[0].symbol]

	for i := 0; i<n; i++ {
		idx := array[i].symbol
		currentSize := lengths[idx]

		for length > currentSize {
			code >>= 1
			length--
		}

		codes[idx] = code
		code++
	}

	return codes
}

func (this *HuffmanTree) Code(val int) uint {
	return this.codes[val]
}

func (this *HuffmanTree) Size(val int) uint {
	return this.sizes[val]
}

func (this *HuffmanTree) encodeByte(bitstream kanzi.OutputBitStream, val byte) error {
	idx := val
	_, err := bitstream.WriteBits(uint64(this.codes[idx]), this.sizes[idx])
	return err
}

func (this *HuffmanTree) decodeByte(bitstream kanzi.InputBitStream) (byte, error) {
	// Empty cache
	currNode := this.current.value

	if currNode != this.root {
		this.current = this.current.next
	}

	for currNode.left != nil || currNode.right != nil {
		r, err := bitstream.ReadBit()

		if err != nil {
			return 0, err
		}

		if r == 0 {
			currNode = currNode.left
		} else {
			currNode = currNode.right
		}
	}

	return currNode.symbol, nil
}

// DECODING_BATCH_SIZE bits must be available in the bitstream
func (this *HuffmanTree) fastDecodeByte(bitstream kanzi.InputBitStream) (byte, error) {
	currNode := this.current.value

	// Use the cache to find a good starting point in the tree
	if currNode == this.root {
		// Read more bits from the bitstream and fetch starting point from cache
		var idx uint64
		idx, _ = bitstream.ReadBits(DECODING_BATCH_SIZE)
		this.current = this.decodingCache[idx]
		currNode = this.current.value
	}

	for currNode.left != nil || currNode.right != nil {

		r, err := bitstream.ReadBit()

		if err != nil {
			return 0, err
		}

		if r == 0 {
			currNode = currNode.left
		} else {
			currNode = currNode.right
		}
	}

	this.current = this.current.next
	return currNode.symbol, nil
}

// ---- Encoder

type HuffmanEncoder struct {
	bitstream kanzi.OutputBitStream
	buffer    []uint
	tree      *HuffmanTree
}

func NewHuffmanEncoder(bs kanzi.OutputBitStream) (*HuffmanEncoder, error) {
	if bs == nil {
		err := errors.New("Invalid null bitstream parameter")
		return nil, err
	}

	this := new(HuffmanEncoder)
	this.bitstream = bs
	this.buffer = make([]uint, 256)

	// Default frequencies
	for i := range this.buffer {
		this.buffer[i] = 1
	}

	var err error
	this.tree, err = newHuffmanTreeFromFrequencies(this.buffer)
	return this, err
}

func (this *HuffmanEncoder) UpdateFrequencies(frequencies []uint) error {
	if frequencies == nil {
		err := errors.New("Invalid null frequencies parameter")
		return err
	}

	var err error
	this.tree, err = newHuffmanTreeFromFrequencies(frequencies)

	if err != nil {
		return err
	}

	prevSize := uint(1)
	egenc, err := NewExpGolombEncoder(this.bitstream, true)

	if err != nil {
		return err
	}

	// Transmit code lengths only, frequencies and code do not matter
	// Unary encode the length difference
	for i := 0; i < len(frequencies); i++ {
		nextSize := this.tree.Size(i)

		// Encode delta as byte (unsigned) but the encoder is sign aware
		egenc.EncodeByte(byte(nextSize - prevSize))
		prevSize = nextSize
	}

	return nil
}

// Dynamically compute the frequencies of the input data
func (this *HuffmanEncoder) Encode(block []byte) (int, error) {
	if block == nil {
		return 0, errors.New("Invalid null block parameter")
	}

	buf := this.buffer // alias

	for i := range buf {
		buf[i] = 0
	}

	for i := range block {
		buf[block[i]]++
	}

	this.UpdateFrequencies(buf)
	return EntropyEncodeArray(this, block)
}

// Frequencies of the data block must have been previously set
func (this *HuffmanEncoder) EncodeByte(val byte) error {
	return this.tree.encodeByte(this.bitstream, val)
}

func (this *HuffmanEncoder) Dispose() {
	this.bitstream.Flush()
}

func (this *HuffmanEncoder) BitStream() kanzi.OutputBitStream {
	return this.bitstream
}

// ---- Decoder

type HuffmanDecoder struct {
	bitstream kanzi.InputBitStream
	buffer    []uint
	tree      *HuffmanTree
}

func NewHuffmanDecoder(bs kanzi.InputBitStream) (*HuffmanDecoder, error) {
	if bs == nil {
		err := errors.New("Invalid null bitstream parameter")
		return nil, err
	}

	this := new(HuffmanDecoder)
	this.bitstream = bs
	this.buffer = make([]uint, 256)

	// Default lengths
	for i := range this.buffer {
		this.buffer[i] = 8
	}

	var err error
	this.tree, err = newHuffmanTreeFromSizes(this.buffer, 8)
	return this, err
}

func (this *HuffmanDecoder) ReadLengths() error {
	buf := this.buffer // alias
	maxSize := uint(1)

	egdec, err := NewExpGolombDecoder(this.bitstream, true)

	if err != nil {
		return err
	}

	szDelta, err := egdec.DecodeByte()

	if err != nil {
		return err
	}

	buf[0] = uint(int8(maxSize) + int8(szDelta))

	// Read lengths
	for i := 1; i < len(buf); i++ {
		szDelta, err := egdec.DecodeByte()

		if err != nil {
			return err
		}

		// Treat szDelta as a signed value (int8) 
		buf[i] = uint(int8(buf[i-1]) + int8(szDelta))

		if maxSize < buf[i] {
			maxSize = buf[i]
		}
	}

	// Create Huffman tree
	this.tree, err = newHuffmanTreeFromSizes(buf, maxSize)
	return err
}

// Rebuild the Huffman tree for each block of data before decoding
func (this *HuffmanDecoder) Decode(block []byte) (int, error) {
	if block == nil {
		return 0, errors.New("Invalid null block parameter")
	}

	this.ReadLengths()
	end2 := len(block)
	end1 := end2 - DECODING_BATCH_SIZE
	i := 0
	var err error

	for i < end1 {
		// Decode fast by reading one byte at a time from the bitstream
		block[i], err = this.tree.fastDecodeByte(this.bitstream)

		if err != nil {
			return i, err
		}

		i++
	}

	for i < end2 {
		// Regular decoding by reading one bit at a time from the bitstream
		block[i], err = this.tree.decodeByte(this.bitstream)

		if err != nil {
			return i, err
		}

		i++
	}

	return end2, nil
}

func (this *HuffmanDecoder) DecodeByte() (byte, error) {
	return this.tree.decodeByte(this.bitstream)
}

func (this *HuffmanDecoder) BitStream() kanzi.InputBitStream {
	return this.bitstream
}

func (this *HuffmanDecoder) Dispose() {
}
