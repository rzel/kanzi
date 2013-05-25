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
	"flag"
	"fmt"
	"os"
	"time"
)

func main() {
	// Define flags
	var verbose *bool = flag.Bool("v", false, "the verbosity level")

	// Parse
	flag.Parse()
	fmt.Printf("verbose: %v\n", *verbose)

	filename := string("c:\\temp\\output.log")
	file, err := os.Create(filename)

	if err != nil {
		fmt.Printf("Cannot create %s", filename)

		return
	}

	before := time.Now()
	bs, _ := bitstream.NewDefaultOutputBitStream(file, 16384)

	for i := uint64(0); i < 1000000; i++ {
		bs.WriteBits(i, 1+uint(i&63))
	}

	bs.Close()
	elapsed := time.Now().Sub(before)
	fmt.Printf("%v\n", elapsed)
	fmt.Printf("Bits written: %v\n", bs.Written())
}
