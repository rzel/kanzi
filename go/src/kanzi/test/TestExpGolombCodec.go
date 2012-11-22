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

package main

import (
	"flag"
	"fmt"
	"kanzi/bitstream"
	"kanzi/entropy"
	"math/rand"
	"os"
	"time"
)

func main() {
	fmt.Printf("\nTestExpGolombCodec")
	var outputName = flag.String("output", "TestExpGolombCodec.log", "optional name of the output file (defaults to TestExpGolombCodec.log)")
	flag.Parse()
	TestCorrectness(*outputName)
	TestSpeed(*outputName)
}

func TestCorrectness(filename string) {
	fmt.Printf("\n\nCorrectness test")

	// Test behavior
	for ii := 1; ii < 20; ii++ {
		fmt.Printf("\nTest %v", ii)
		var values []byte
		rand.Seed(time.Now().UTC().UnixNano())

		if ii == 3 {
			values = []byte{0, 0, 32, 15, -4 & 0xFF, 16, 0, 16, 0, 7, -1 & 0xFF, -4 & 0xFF, -32 & 0xFF, 0, 31, -1 & 0xFF}
		} else if ii == 2 {
			values = []byte{0x3d, 0x4d, 0x54, 0x47, 0x5a, 0x36, 0x39, 0x26, 0x72, 0x6f, 0x6c, 0x65, 0x3d, 0x70, 0x72, 0x65}
		} else if ii == 1 {
			values = []byte{65, 71, 74, 66, 76, 65, 69, 77, 74, 79, 68, 75, 73, 72, 77, 68, 78, 65, 79, 79, 78, 66, 77, 71, 64, 70, 74, 77, 64, 67, 71, 64}
		} else {
			values = make([]byte, 32)

			for i := range values {
				values[i] = byte(64 + 3*ii + rand.Intn(ii+1))
			}
		}

		fmt.Printf("\nOriginal: ")

		for i := range values {
			fmt.Printf("%d ", values[i])
		}

		fmt.Printf("\nEncoded: ")
		oFile, err := os.Create(filename)

		if err != nil {
			fmt.Printf("Cannot create %s", filename)
			os.Exit(1)
		}

		defer oFile.Close()
		obs, _ := bitstream.NewDefaultOutputBitStream(oFile)
		dbgbs, _ := bitstream.NewDebugOutputBitStream(obs, os.Stdout)
		dbgbs.ShowByte(true)
		dbgbs.Mark(true)
		fpc, _ := entropy.NewExpGolombEncoder(dbgbs, true)
		_, err = fpc.Encode(values)

		if err != nil {
			fmt.Printf("Error during encoding: %s", err)
			os.Exit(1)
		}

		fpc.Dispose()
		dbgbs.Close()
		fmt.Println()

		iFile, err2 := os.Open(filename)

		if err2 != nil {
			fmt.Printf("Cannot open %s", filename)
			os.Exit(1)
		}

		defer iFile.Close()
		ibs, _ := bitstream.NewDefaultInputBitStream(iFile)
		dbgbs2, _ := bitstream.NewDebugInputBitStream(ibs, os.Stdout)
		dbgbs2.ShowByte(true)
		//dbgbs2.Mark(true)

		fpd, _ := entropy.NewExpGolombDecoder(dbgbs2, true)

		ok := true
		values2 := make([]byte, len(values))
		_, err = fpd.Decode(values2)

		if err != nil {
			fmt.Printf("Error during decoding: %s", err)
			os.Exit(1)
		}

		fmt.Printf("\nDecoded: ")

		for i := range values2 {
			fmt.Printf("%v ", values2[i])

			if values[i] != values2[i] {
				ok = false
			}
		}

		if ok == true {
			fmt.Printf("\nIdentical")
		} else {
			fmt.Printf("\n! *** Different *** !")
			os.Exit(1)
		}

		fpd.Dispose()
		fmt.Printf("\n")
	}
}

func TestSpeed(filename string) {
	fmt.Printf("\n\nSpeed test\n")
	repeats := [16]int{3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9, 3}

	for jj := 0; jj < 3; jj++ {
		fmt.Printf("Test %v\n", jj+1)
		oFile, err := os.Create(filename)

		if err != nil {
			fmt.Printf("Cannot create %s", filename)
			return
		}

		defer oFile.Close()
		iFile, err2 := os.Open(filename)

		if err2 != nil {
			fmt.Printf("Cannot open %s", filename)
			return
		}

		defer iFile.Close()
		delta1 := int64(0)
		delta2 := int64(0)
		values1 := make([]byte, 50000)
		values2 := make([]byte, 50000)
		obs, _ := bitstream.NewDefaultOutputBitStream(oFile)
		rc, _ := entropy.NewExpGolombEncoder(obs, true)
		ibs, _ := bitstream.NewDefaultInputBitStream(iFile)
		rd, _ := entropy.NewExpGolombDecoder(ibs, true)

		for ii := 0; ii < 2000; ii++ {
			idx := jj

			for i := 0; i < len(values1); i++ {
				i0 := i

				length := repeats[idx]
				idx = (idx + 1) & 0x0F

				if i0+length >= len(values1) {
					length = 1
				}

				for j := i0; j < i0+length; j++ {
					values1[j] = byte(uint(i0) & 0xFF)
					i++
				}
			}

			// Encode
			before := time.Now()
			_, err := rc.Encode(values1)

			if err != nil {
				fmt.Printf("An error occured during encoding: %v\n", err)
				os.Exit(1)
			}

			after := time.Now()
			delta1 += after.Sub(before).Nanoseconds()
		}

		rc.Dispose()
		obs.Close()

		for ii := 0; ii < 2000; ii++ {
			// Decode
			before := time.Now()
			_, err = rd.Decode(values2)

			if err != nil {
				fmt.Printf("An error occured during decoding: %v\n", err)
				os.Exit(1)
			}

			after := time.Now()
			delta2 += after.Sub(before).Nanoseconds()
		}

		rd.Dispose()
		ibs.Close()

		fmt.Printf("Encode [ms]: %v\n", delta1/1000000)
		fmt.Printf("Decode [ms]: %v\n", delta2/1000000)
	}
}
