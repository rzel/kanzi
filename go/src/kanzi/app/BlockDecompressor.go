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
	"kanzi"
	"kanzi/bitstream"
	"kanzi/entropy"
	"kanzi/function"
	"os"
	"strings"
	"time"
)

const (
	BITSTREAM_TYPE           = 0x4B4E5A // "KNZ"
	BITSTREAM_FORMAT_VERSION = 0
	MAX_BLOCK_HEADER_SIZE    = 7
	ERR_MISSING_FILENAME     = -1
	ERR_BLOCK_SIZE           = -2
	ERR_INVALID_CODEC        = -3
	ERR_CREATE_DECOMPRESSOR  = -4
	ERR_OUTPUT_IS_DIR        = -5
	ERR_OVERWRITE_FILE       = -6
	ERR_CREATE_FILE          = -7
	ERR_CREATE_BITSTREAM     = -8
	ERR_OPEN_FILE            = -9
	ERR_READ_FILE            = -10
	ERR_WRITE_FILE           = -11
	ERR_PROCESS_BLOCK        = -12
	ERR_CREATE_CODEC         = -13
	ERR_INVALID_FILE         = -14
	ERR_STREAM_VERSION       = -15
)

type BlockDecompressor struct {
	debug      bool
	silent     bool
	overwrite  bool
	inputName  string
	outputName string
	blockCodec *function.BlockCodec
}

func NewBlockDecompressor() (*BlockDecompressor, error) {
	this := new(BlockDecompressor)

	// Define flags
	var help = flag.Bool("help", false, "display the help message")
	var debug = flag.Bool("debug", false, "display the size of the completely decoded block")
	var overwrite = flag.Bool("overwrite", false, "overwrite the output file if it already exists")
	var silent = flag.Bool("silent", false, "silent mode: no output (except warnings and errors)")
	var inputName = flag.String("input", "", "mandatory name of the input file to decode")
	var outputName = flag.String("output", "", "optional name of the output file")

	// Parse
	flag.Parse()

	if *help == true {
		printOut("-help              : display this message", true)
		printOut("-debug             : display the size of the encoded block pre-entropy coding", true)
		printOut("-silent            : silent mode: no output (except warnings and errors)", true)
		printOut("-overwrite         : overwrite the output file if it already exists", true)
		printOut("-input=<filename>  : mandatory name of the input file to encode", true)
		printOut("-output=<filename> : optional name of the output file", true)
		os.Exit(0)
	}

	if *silent == true && *debug == true {
		printOut("Warning: both 'silent' and 'debug' options were selected, ignoring 'debug'", true)
		*debug = false
	}

	if len(*inputName) == 0 {
		fmt.Printf("Missing input file name, exiting ...\n")
		os.Exit(ERR_MISSING_FILENAME)
	}

	if len(*outputName) == 0 {
		if strings.HasSuffix(*inputName, ".knz") == false {
			printOut("Warning: the input file name does not end with the .KNZ extension", true)
			*outputName = *inputName + ".tmp"
		} else {
			*outputName = strings.TrimRight(*inputName, ".knz")
		}
	}

	this.debug = *debug
	this.silent = *silent
	this.inputName = *inputName
	this.outputName = *outputName
	this.overwrite = *overwrite
	var err error
	this.blockCodec, err = function.NewBlockCodec(uint(0))
	return this, err
}

func main() {
	bd, err := NewBlockDecompressor()

	if err != nil {
		fmt.Printf("Failed to create block decompressor: %v\n", err)
		os.Exit(ERR_CREATE_DECOMPRESSOR)
	}

	code, _ := bd.call()
	os.Exit(code)
}

// Return exit code, number of bits written
func (this *BlockDecompressor) call() (int, uint64) {
	var msg string
	printOut("Input file name set to '"+this.inputName+"'", this.debug)
	printOut("Output file name set to '"+this.outputName+"'", this.debug)
	msg = fmt.Sprintf("Debug set to %t", this.debug)
	printOut(msg, this.debug)
	msg = fmt.Sprintf("Overwrite set to %t", this.debug)
	printOut(msg, this.overwrite)

	output, err := os.OpenFile(this.outputName, os.O_RDWR, 666)

	if err == nil {
		// File exists
		if this.overwrite == false {
			fmt.Printf("The output file '%v' exists and the 'overwrite' command ", this.outputName)
			fmt.Println("line option has not been provided")
			output.Close()
			return ERR_OVERWRITE_FILE, 0
		}
	} else {
		// File does not exist, create
		output, err = os.Create(this.outputName)

		if err != nil {
			fmt.Printf("Cannot open output file '%v' for writing: %v\n", this.outputName, err)
			return ERR_CREATE_FILE, 0
		}
	}

	defer output.Close()
	delta := int64(0)
	decoded := 0
	sum := uint64(0)
	step := 0
	printOut("Decoding ...", !this.silent)

	// Decode
	var entropyDecoder kanzi.EntropyDecoder
	input, err := os.Open(this.inputName)

	if err != nil {
		fmt.Printf("Cannot open input file '%v': %v\n", this.inputName, err)
		return ERR_OPEN_FILE, sum
	}

	defer input.Close()
	ibs, err := bitstream.NewDefaultInputBitStream(input)

	if err != nil {
		fmt.Printf("Cannot create input bit stream: %v\n", err)
		return ERR_CREATE_BITSTREAM, sum
	}

	// Read header
	fileType, err := ibs.ReadBits(24)

	if err != nil {
		fmt.Printf("Error reading header from input file: %v\n", err)
		return ERR_READ_FILE, sum
	}

	// Sanity check
	if fileType != BITSTREAM_TYPE {
		errMsg := fmt.Sprintf("Invalid stream type: expected %#X, got %#X\n", BITSTREAM_TYPE, fileType)
		fmt.Printf(errMsg)
		return ERR_INVALID_FILE, sum
	}

	header, err := ibs.ReadBits(40)

	if err != nil {
		fmt.Printf("Error reading input file: %v\n", err)
		return ERR_READ_FILE, sum
	}

	version := int((header >> 32) & 0xFF)

	// Sanity check
	if version < BITSTREAM_FORMAT_VERSION {
		fmt.Printf("Cannot read this version of the stream: %d\n", version)
		return ERR_STREAM_VERSION, sum
	}

	entropyType := byte((header >> 24) & 0xFF)
	blockSize := uint(header & 0xFFFFFF)

	if blockSize > uint(16*1024*1024-MAX_BLOCK_HEADER_SIZE) {
		fmt.Printf("Invalid block size read from file: %d", blockSize)
		return ERR_BLOCK_SIZE, sum
	}

	buffer := make([]byte, blockSize+MAX_BLOCK_HEADER_SIZE)
	firstBlock := true
	msg = fmt.Sprintf("Block size set to %d", blockSize)
	printOut(msg, this.debug)

	if entropyType == 'H' {
		printOut("Using Huffman entropy codec", this.debug)
	} else if entropyType == 'R' {
		printOut("Using Range entropy codec", this.debug)
	} else if entropyType == 'P' {
		printOut("Using PAQ entropy codec", this.debug)
	} else if entropyType == 'F' {
		printOut("Using FPAQ entropy codec", this.debug)
	} else {
		printOut("Using no entropy codec", this.debug)
	}

	// Decode next block
	for decoded != 0 || firstBlock == true {
		firstBlock = false

		switch entropyType {
		// Each block is decoded separately
		// Rebuild the entropy decoder to reset block statistics
		case 'H':
			entropyDecoder, err = entropy.NewHuffmanDecoder(ibs)

		case 'R':
			entropyDecoder, err = entropy.NewRangeDecoder(ibs)

		case 'P':
			predictor, _ := entropy.NewPAQPredictor()
			entropyDecoder, err = entropy.NewBinaryEntropyDecoder(ibs, predictor)

		case 'F':
			predictor, _ := entropy.NewFPAQPredictor()
			entropyDecoder, err = entropy.NewFPAQEntropyDecoder(ibs, predictor)

		case 'N':
			if entropyDecoder == nil {
				entropyDecoder, err = entropy.NewNullEntropyDecoder(ibs)
			}

		default:
			fmt.Printf("Invalid entropy codec type: '%c'\n", entropyType)
			return ERR_INVALID_CODEC, sum
		}

		if err != nil {
			fmt.Printf("Failed to create entropy decoder: %v\n", err)
			return ERR_CREATE_CODEC, sum
		}

		before := time.Now()
		decoded, err = this.blockCodec.Decode(buffer, entropyDecoder)
		after := time.Now()
		delta += after.Sub(before).Nanoseconds()

		if decoded < 0 || err != nil {
			if err != nil {
				fmt.Printf("Error in block codec inverse(): %v\n", err)
			} else {
				fmt.Println("Error in block codec inverse()")
			}

			return ERR_PROCESS_BLOCK, sum
		}

		// Display block size after entropy decoding + block transform
		msg := fmt.Sprintf("Block %d: %d byte(s)", step, decoded)
		printOut(msg, this.debug)

		_, err = output.Write(buffer[0:decoded])

		if err != nil {
			fmt.Printf("Failed to write next block: %v\n", err)
			return ERR_WRITE_FILE, sum
		}

		sum += uint64(decoded)
		step++
		entropyDecoder.Dispose()
	}

	delta /= 1000000 // convert to ms
	printOut("", !this.silent)
	msg = fmt.Sprintf("Decoding took %d ms", delta)
	printOut(msg, !this.silent)
	msg = fmt.Sprintf("Decoded:          %d", sum)
	printOut(msg, !this.silent)
	msg = fmt.Sprintf("Troughput (KB/s): %d", ((sum*uint64(1000))>>10)/uint64(delta))
	printOut(msg, !this.silent)
	printOut("", !this.silent)
	return 0, ibs.Read()
}

func printOut(msg string, print bool) {
	if print == true {
		fmt.Println(msg)
	}
}
