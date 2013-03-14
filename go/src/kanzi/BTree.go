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

import (
	"errors"
)

type IntBTNode struct {
	value int
	count uint
	left  *IntBTNode
	right *IntBTNode
}

type IntBTree struct {
	root *IntBTNode
	size int
}

func (this *IntBTree) Size() int {
	return this.size
}

func (this *IntBTree) Add(val int) {
	node := &IntBTNode{value: val, count: 1}

	if this.root == nil {
		this.root = node
	} else {
		this.scanAndAdd(this.root, node)
	}

	this.size++
}

func (this *IntBTree) scanAndAdd(parent, node *IntBTNode) {
	if parent == nil {
		return
	}

	if node.value == parent.value {
		parent.count++
	} else if node.value < parent.value {
		if parent.left == nil {
			parent.left = node
		} else {
			this.scanAndAdd(parent.left, node)
		}
	} else {
		if parent.right == nil {
			parent.right = node
		} else {
			this.scanAndAdd(parent.right, node)
		}
	}
}

func (this *IntBTree) Remove(value int) *IntBTNode {
	if this.root == nil {
		return nil
	}

	res := this.scanAndRemove(this.root, nil, value, true)

	if res == this.root && this.root.count == 0 {
		this.root = nil

		if res.right != nil {
			this.root = res.right
		} else if res.left != nil {
			this.root = res.left
		}
	}

	if res != nil {
		this.size--
	}

	return res
}

func (this *IntBTree) scanAndRemove(current, prev *IntBTNode, value int, right bool) *IntBTNode {
	if current == nil {
		return nil
	}

	if value < current.value {
		if current.left == nil {
			return nil
		} else {
			return this.scanAndRemove(current.left, current, value, false)
		}
	} else if value > current.value {
		if current.right == nil {
			return nil
		} else {
			return this.scanAndRemove(current.right, current, value, true)
		}
	}

	current.count--

	if current.count == 0 && prev != nil {
		if right {
			if current.left != nil {
				prev.right = current.left

				if current.right != nil {
					// Re-insert right branch
					this.scanAndAdd(this.root, current.right)
				}
			} else {
				prev.right = current.right
			}
		} else {
			prev.left = current.left

			if current.right != nil {
				// Re-insert right branch
				this.scanAndAdd(this.root, current.right)
			}
		}
	}

	return current
}

func (this *IntBTree) Scan(callback func(node *IntBTNode), reverse bool) {
	scanAndCall(this.root, callback, reverse) // visitor pattern    
}

func scanAndCall(current *IntBTNode, callback func(node *IntBTNode), reverse bool) {
	if current == nil {
		return
	}

	if reverse == false {
		if current.left != nil {
			scanAndCall(current.left, callback, reverse)
		}

		for i := current.count; i > 0; i-- {
			callback(current)
		}

		if current.right != nil {
			scanAndCall(current.right, callback, reverse)
		}
	} else {
		if current.right != nil {
			scanAndCall(current.right, callback, reverse)
		}

		for i := current.count; i > 0; i-- {
			callback(current)
		}

		if current.left != nil {
			scanAndCall(current.left, callback, reverse)
		}
	}
}

func (this *IntBTree) Min() (int, error) {
	if this.root == nil {
		return 0, errors.New("Tree is empty")
	}

	node := this.root
	min := node.value

	for node != nil {
		min = node.value
		node = node.left
	}

	return min, nil
}

func (this *IntBTree) Max() (int, error) {
	if this.root == nil {
		return 0, errors.New("Tree is empty")
	}

	node := this.root
	max := node.value

	for node != nil {
		max = node.value
		node = node.right
	}

	return max, nil
}
