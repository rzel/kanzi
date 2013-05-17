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

package main

import (
	"fmt"
	"kanzi/transform"
	"math/rand"
	"os"
	"time"
)

func main() {
	fmt.Printf("\nCorrectness test")

	for ii := 0; ii < 20; ii++ {
		rnd := rand.New(rand.NewSource(time.Now().UnixNano()))
		var input []byte
		if ii == 0 {
			input = []byte{5, 2, 4, 7, 0, 0, 7, 1, 7}
		} else {
			input = make([]byte, 32)

			for i := 0; i < len(input); i++ {
				input[i] = byte(65 + rnd.Intn(64))
			}
		}

		size := len(input)
		mtft, _ := transform.NewMTFT(uint(size))
		transform := make([]byte, size)
		reverse := make([]byte, size)

		for i := 0; i < len(input); i++ {
			transform[i] = input[i]
		}

		fmt.Printf("\nTest %d", (ii + 1))
		fmt.Printf("\nInput     : ")

		for i := 0; i < len(input); i++ {
			fmt.Printf("%d ", input[i])
		}

		transform = mtft.Forward(transform)
		fmt.Printf("\nTransform : ")

		for i := 0; i < len(input); i++ {
			fmt.Printf("%d ", transform[i])
		}

		reverse = mtft.Inverse(transform)
		fmt.Printf("\nReverse   : ")

		for i := 0; i < len(input); i++ {
			fmt.Printf("%d ", reverse[i])
		}

		fmt.Printf("\n")
		ok := true

		for i := 0; i < len(input); i++ {
			if reverse[i] != input[i] {
				ok = false
				break
			}
		}

		if ok == true {
			fmt.Printf("Identical\n")
		} else {
			fmt.Printf("Different\n")
		}
	}

	// Speed Test
	iter := 20000
	size := 10000
	fmt.Printf("\n\nSpeed test\n")
	fmt.Printf("Iterations: %v\n", iter)

	for jj := 0; jj < 3; jj++ {
		input := make([]byte, size)
		var output []byte
		var reverse []byte
		mtft, _ := transform.NewMTFT(uint(size))
		delta1 := int64(0)
		delta2 := int64(0)

		for ii := 0; ii < iter; ii++ {
			for i := 0; i < len(input); i++ {
				input[i] = byte(rand.Intn(64))
			}

			before := time.Now()
			output = mtft.Forward(input)
			after := time.Now()
			delta1 += after.Sub(before).Nanoseconds()
			before = time.Now()
			reverse = mtft.Inverse(output)
			after = time.Now()
			delta2 += after.Sub(before).Nanoseconds()
		}

		idx := -1

		// Sanity check
		for i := range input {
			if input[i] != reverse[i] {
				idx = i
				break
			}
		}

		if idx >= 0 {
			fmt.Printf("Failure at index %v (%v <-> %v)\n", idx, input[idx], reverse[idx])
			os.Exit(1)
		}

		fmt.Printf("\nMTFT Forward transform [ms]: %v", delta1/1000000)
		fmt.Printf("\nThroughput [KB/s]: %d", (int64(iter*size))*1000000/delta1*1000/1024)
		fmt.Printf("\nMTFT Reverse transform [ms]: %v", delta2/1000000)
		fmt.Printf("\nThroughput [KB/s]: %d", (int64(iter*size))*1000000/delta2*1000/1024)
	}
}
