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

package entropy

import (
	"container/list"
	"errors"
	"kanzi"
	"sort"
)

const (
	DECODING_BATCH_SIZE        = 10            // in bits
	DEFAULT_HUFFMAN_CHUNK_SIZE = uint(1 << 16) // 64 KB by default
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

// Return the number of codes generated
func GenerateCanonicalCodes(sizes, codes []uint) int {
	array := make(HuffmanNodeArray, len(sizes))
	n := 0

	for i := range array {
		codes[i] = 0

		if sizes[i] > 0 {
			array[n] = &HuffmanNode{symbol: byte(i), weight: sizes[i]}
			n++
		}
	}

	// Sort by decreasing size (first key) and increasing value (second key)
	sort.Sort(array[0:n])
	code := uint(0)
	length := sizes[array[0].symbol]

	for i := 0; i < n; i++ {
		idx := array[i].symbol
		currentSize := sizes[idx]

		if length > currentSize {
			code >>= (length - currentSize)
			length = currentSize
		}

		codes[idx] = code
		code++
	}

	return n
}

// ---- Encoder

type HuffmanEncoder struct {
	bitstream kanzi.OutputBitStream
	buffer    []uint
	codes     []uint
	sizes     []uint
	chunkSize int
}

// The chunk size indicates how many bytes are encoded (per block) before
// resetting the frequency stats. 0 means that frequencies calculated at the
// beginning of the block apply to the whole block
// Since the number of args is variable, this function can be called like this:
// NewHuffmanEncoder(bs) or NewHuffmanEncoder(bs, 16384)
// The default chunk size is 65536 bytes.
func NewHuffmanEncoder(bs kanzi.OutputBitStream, chunkSizes ...uint) (*HuffmanEncoder, error) {
	if bs == nil {
		return nil, errors.New("Invalid null bitstream parameter")
	}

	if len(chunkSizes) > 1 {
		return nil, errors.New("At most one chunk size can be provided")
	}

	chkSize := DEFAULT_HUFFMAN_CHUNK_SIZE

	if len(chunkSizes) == 1 {
		chkSize = chunkSizes[0]
	}

	if chkSize != 0 && chkSize < 1024 {
		return nil, errors.New("The chunk size must be a least 1024")
	}

	if chkSize > 1<<30 {
		return nil, errors.New("The chunk size must be a least most 2^30")
	}

	this := new(HuffmanEncoder)
	this.bitstream = bs
	this.buffer = make([]uint, 256)
	this.codes = make([]uint, 256)
	this.sizes = make([]uint, 256)
	this.chunkSize = int(chkSize)

	// Default frequencies, sizes and codes
	for i := 0; i < 256; i++ {
		this.buffer[i] = 1
		this.sizes[i] = 8
		this.codes[i] = uint(i)
	}

	return this, nil
}

func createTreeFromFrequencies(frequencies, sizes_ []uint) *HuffmanNode {
	array := make(HuffmanNodeArray2, 256)
	n := 0

	for i := range array {
		sizes_[i] = 0

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

	fillTree(rootNode, 0, sizes_)
	return rootNode
}

// Fill size and code arrays
func fillTree(node *HuffmanNode, depth uint, sizes_ []uint) {
	if node.left == nil && node.right == nil {
		idx := node.symbol
		sizes_[idx] = depth
		return
	}

	if node.left != nil {
		fillTree(node.left, depth+1, sizes_)
	}

	if node.right != nil {
		fillTree(node.right, depth+1, sizes_)
	}
}

// Rebuild Huffman tree
func (this *HuffmanEncoder) UpdateFrequencies(frequencies []uint) error {
	if frequencies == nil || len(frequencies) != 256 {
		return errors.New("Invalid frequencies parameter")
	}

	// Create tree from frequencies
	createTreeFromFrequencies(frequencies, this.sizes)

	// Create canonical codes
	GenerateCanonicalCodes(this.sizes, this.codes)
	prevSize := uint(1)
	egenc, err := NewExpGolombEncoder(this.bitstream, true)

	if err != nil {
		return err
	}

	// Transmit code lengths only, frequencies and codes do not matter
	// Unary encode the length difference
	for i := 0; i < 256; i++ {
		egenc.EncodeByte(byte(this.sizes[i] - prevSize))
		prevSize = this.sizes[i]
	}

	return nil
}

// Dynamically compute the frequencies for every chunk of data in the block
func (this *HuffmanEncoder) Encode(block []byte) (int, error) {
	if block == nil {
		return 0, errors.New("Invalid null block parameter")
	}

	buf := this.buffer // aliasing
	end := len(block)
	startChunk := 0
	sizeChunk := this.chunkSize

	if sizeChunk == 0 {
		sizeChunk = end
	}

	if startChunk+sizeChunk >= end {
		sizeChunk = end - startChunk
	}

	endChunk := startChunk + sizeChunk

	for startChunk < end {
		for i := range buf {
			buf[i] = 0
		}

		for i := startChunk; i < endChunk; i++ {
			buf[block[i]]++
		}

		// Rebuild Huffman tree
		this.UpdateFrequencies(buf)

		for i := startChunk; i < endChunk; i++ {
			if err := this.EncodeByte(block[i]); err != nil {
				return i, err
			}
		}

		startChunk = endChunk

		if startChunk+sizeChunk >= end {
			sizeChunk = end - startChunk
		}

		endChunk = startChunk + sizeChunk
	}

	return len(block), nil
}

// Frequencies of the data block must have been previously set
func (this *HuffmanEncoder) EncodeByte(val byte) error {
	_, err := this.bitstream.WriteBits(uint64(this.codes[val]), this.sizes[val])
	return err
}

func (this *HuffmanEncoder) Dispose() {
	this.bitstream.Flush()
}

func (this *HuffmanEncoder) BitStream() kanzi.OutputBitStream {
	return this.bitstream
}

// ---- Decoder

type HuffmanDecoder struct {
	bitstream     kanzi.InputBitStream
	codes         []uint
	sizes         []uint
	root          *HuffmanNode
	decodingCache []*HuffmanCacheData
	current       *HuffmanCacheData
	chunkSize     int
}

// The chunk size indicates how many bytes are encoded (per block) before
// resetting the frequency stats. 0 means that frequencies calculated at the
// beginning of the block apply to the whole block
// Since the number of args is variable, this function can be called like this:
// NewHuffmanDecoder(bs) or NewHuffmanDecoder(bs, 16384)
// The default chunk size is 65536 bytes.
func NewHuffmanDecoder(bs kanzi.InputBitStream, chunkSizes ...uint) (*HuffmanDecoder, error) {
	if bs == nil {
		return nil, errors.New("Invalid null bitstream parameter")
	}

	if len(chunkSizes) > 1 {
		return nil, errors.New("At most one chunk size can be provided")
	}

	chkSize := DEFAULT_HUFFMAN_CHUNK_SIZE

	if len(chunkSizes) == 1 {
		chkSize = chunkSizes[0]
	}

	if chkSize != 0 && chkSize < 1024 {
		return nil, errors.New("The chunk size must be a least 1024")
	}

	if chkSize > 1<<30 {
		return nil, errors.New("The chunk size must be a least most 2^30")
	}

	this := new(HuffmanDecoder)
	this.bitstream = bs
	this.sizes = make([]uint, 256)
	this.codes = make([]uint, 256)
	this.decodingCache = make([]*HuffmanCacheData, 1<<DECODING_BATCH_SIZE)
	this.chunkSize = int(chkSize)

	// Default lengths
	for i := range this.sizes {
		this.sizes[i] = 8
	}

	// Create canonical codes
	GenerateCanonicalCodes(this.sizes, this.codes)

	// Create tree from code sizes
	this.root = this.createTreeFromSizes(8)
	buildDecodingCache(this.root, this.decodingCache)
	this.current = &HuffmanCacheData{value: this.root} // point to root
	return this, nil
}

func buildDecodingCache(rootNode *HuffmanNode, data []*HuffmanCacheData) []*HuffmanCacheData {
	end := 1 << DECODING_BATCH_SIZE
	previousData := &HuffmanCacheData{}
	n := 0

	// Create an array storing a list of tree nodes (shortcuts) for each input value
	for val := 0; val < end; val++ {
		shift := DECODING_BATCH_SIZE - 1
		firstAdded := false

		for shift >= 0 {
			// Start from root
			currentNode := rootNode

			// Process next bit
			for currentNode.left != nil || currentNode.right != nil {

				if (val>>uint(shift))&1 == 0 {
					currentNode = currentNode.left
				} else {
					currentNode = currentNode.right
				}

				shift--

				if shift < 0 {
					break
				}
			}

			currentData := &HuffmanCacheData{value: currentNode}

			// The cache is made of linked nodes
			previousData.next = currentData
			previousData = currentData

			if firstAdded == false {
				// Add first node of list to array (whether it is a leaf or not)
				data[n] = currentData
				n++
				firstAdded = true
			}
		}

		previousData.next = &HuffmanCacheData{value: rootNode}
		previousData = previousData.next
	}

	return data
}

func (this *HuffmanDecoder) createTreeFromSizes(maxSize uint) *HuffmanNode {
	codeMap := make(map[int]*HuffmanNode)
	sum := uint(1 << maxSize)
	keys := make([]int, 256)
	tree := kanzi.IntBTree{} //use binary tree
	codeMap[0] = &HuffmanNode{symbol: byte(0), weight: sum}
	tree.Add(0) // add key(0,0)

	// Create node for each (present) symbol and add to map
	for i := range this.sizes {
		size := this.sizes[i]

		if size == 0 {
			continue
		}

		key := int((size << 16) | this.codes[i])
		tree.Add(key)
		value := &HuffmanNode{symbol: byte(i), weight: sum >> size}
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

func (this *HuffmanDecoder) ReadLengths() error {
	buf := this.sizes // alias

	egdec, err := NewExpGolombDecoder(this.bitstream, true)

	if err != nil {
		return err
	}

	szDelta, err := egdec.DecodeByte()

	if err != nil {
		return err
	}

	buf[0] = uint(int8(1) + int8(szDelta))
	maxSize := buf[0]

	// Read lengths
	for i := 1; i < 256; i++ {
		if szDelta, err = egdec.DecodeByte(); err != nil {
			return err
		}

		// Treat szDelta as a signed value (int8)
		buf[i] = uint(int8(buf[i-1]) + int8(szDelta))

		if maxSize < buf[i] {
			maxSize = buf[i]
		}
	}

	// Create canonical codes
	GenerateCanonicalCodes(buf, this.codes)

	// Create tree from code sizes
	this.root = this.createTreeFromSizes(maxSize)
	buildDecodingCache(this.root, this.decodingCache)
	this.current = &HuffmanCacheData{value: this.root} // point to root
	return nil
}

// Rebuild the Huffman tree for each chunk of data in the block
// Use fastDecodeByte until the near end of chunk or block.
func (this *HuffmanDecoder) Decode(block []byte) (int, error) {
	if block == nil {
		return 0, errors.New("Invalid null block parameter")
	}

	end := len(block)
	startChunk := 0
	sizeChunk := this.chunkSize

	if sizeChunk == 0 {
		sizeChunk = len(block)
	}

	if startChunk+sizeChunk >= end {
		sizeChunk = end - startChunk
	}

	endChunk := startChunk + sizeChunk

	for startChunk < end {
		// Reinitialize the Huffman tree
		this.ReadLengths()
		endChunk1 := endChunk - DECODING_BATCH_SIZE
		i := startChunk
		var err error

		for i < endChunk1 {
			// Fast decoding by reading several bits at a time from the bitstream
			if block[i], err = this.fastDecodeByte(); err != nil {
				return i, err
			}

			i++
		}

		for i < endChunk {
			// Regular decoding by reading one bit at a time from the bitstream
			if block[i], err = this.DecodeByte(); err != nil {
				return i, err
			}

			i++
		}

		startChunk = endChunk

		if startChunk+sizeChunk >= end {
			sizeChunk = end - startChunk
		}

		endChunk = startChunk + sizeChunk
	}

	return len(block), nil
}

func (this *HuffmanDecoder) DecodeByte() (byte, error) {
	// Empty cache
	currNode := this.current.value

	if currNode != this.root {
		this.current = this.current.next
	}

	for currNode.left != nil || currNode.right != nil {
		r, err := this.bitstream.ReadBit()

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
func (this *HuffmanDecoder) fastDecodeByte() (byte, error) {
	currNode := this.current.value

	// Use the cache to find a good starting point in the tree
	if currNode == this.root {
		// Read more bits from the bitstream and fetch starting point from cache
		idx, _ := this.bitstream.ReadBits(DECODING_BATCH_SIZE)
		this.current = this.decodingCache[idx]
		currNode = this.current.value
	}

	for currNode.left != nil || currNode.right != nil {
		r, err := this.bitstream.ReadBit()

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

func (this *HuffmanDecoder) BitStream() kanzi.InputBitStream {
	return this.bitstream
}

func (this *HuffmanDecoder) Dispose() {
}
