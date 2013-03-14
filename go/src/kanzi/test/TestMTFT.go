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
	"kanzi/transform"
	"fmt"
	"math/rand"
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
	fmt.Printf("\nSpeed test")

	input := make([]byte, 10000)
	transformed := make([]byte, len(input))
	mtft, _ := transform.NewMTFT(uint(0))
	//reversed := make([]byte, len(input))
	delta1 := int64(0)
	delta2 := int64(0)
	iter := 20000

	for ii := 0; ii < iter; ii++ {
		for i := 0; i < len(input); i++ {
			input[i] = byte(rand.Intn(64))
		}

		before := time.Now()
		transformed = mtft.Forward(input)
		after := time.Now()
		delta1 += after.Sub(before).Nanoseconds()
		before = time.Now()
		mtft.Inverse(transformed)
		after = time.Now()
		delta2 += after.Sub(before).Nanoseconds()
	}

	fmt.Printf("\nIterations: %v", iter)
	fmt.Printf("\nMTFT Forward transform [ms]: %v", delta1/1000000)
	fmt.Printf("\nMTFT Reverse transform [ms]: %v", delta2/1000000)
}
