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
	"kanzi/bitstream"
	"fmt"
	"os"
	"time"
)

func main() {
	filename := string("c:\\temp\\output.log")
	file, err := os.Open(filename)

	if err != nil {
		fmt.Printf("Cannot open %s", filename)

		return
	}

	before := time.Now()
	sum := int64(0)
	bs, _ := bitstream.NewDefaultInputBitStream(file, 16384)

	for i := uint64(0); i < 1000000; i++ {
		//		fmt.Println(bs.ReadBits(1 + uint(i&63)))
		bs.ReadBits(1 + uint(i&63))
	}

	bs.Close()
	elapsed := time.Now().Sub(before)
	sum += int64(elapsed)
	fmt.Printf("%v\n", elapsed)
	fmt.Printf("Bits read: %v\n", bs.Read())
}
