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
	MAX_BLOCK_HEADER_SIZE    = function.MAX_BLOCK_HEADER_SIZE
	ERR_MISSING_FILENAME     = -1
	ERR_BLOCK_SIZE           = -2
	ERR_INVALID_CODEC        = -3
	ERR_CREATE_COMPRESSOR    = -4
	ERR_OUTPUT_IS_DIR        = -5
	ERR_OVERWRITE_FILE       = -6
	ERR_CREATE_FILE          = -7
	ERR_CREATE_BITSTREAM     = -8
	ERR_OPEN_FILE            = -9
	ERR_READ_FILE            = -10
	ERR_WRITE_FILE           = -11
	ERR_PROCESS_BLOCK        = -12
	ERR_CREATE_CODEC         = -13
	WARN_EMPTY_INPUT         = -128
)

type BlockCompressor struct {
	debug       bool
	silent      bool
	overwrite   bool
	entropyType byte
	inputName   string
	outputName  string
	blockCodec  *function.BlockCodec
	blockSize   int
}

func NewBlockCompressor() (*BlockCompressor, error) {
	this := new(BlockCompressor)

	// Define flags
	var help = flag.Bool("help", false, "display the help message")
	var debug = flag.Bool("debug", false, "display the size of the encoded block pre-entropy coding")
	var silent = flag.Bool("silent", false, "silent mode: no output (except warnings and errors)")
	var overwrite = flag.Bool("overwrite", false, "overwrite the output file if it already exists")
	var inputName = flag.String("input", "", "mandatory name of the input file to encode")
	var outputName = flag.String("output", "", "optional name of the output file (defaults to <input.knz>)")
	var blockSize = flag.Int("block", 100000, "size of the block in bytes (max 16 MB / default 100 KB)")
	var entropy = flag.String("entropy", "Huffman", "Entropy codec to use [None|Huffman|Range|PAQ|FPAQ]")

	// Parse
	flag.Parse()

	if *help == true {
		printOut("-help              : display this message", true)
		printOut("-debug             : display the size of the encoded block pre-entropy coding", true)
		printOut("-silent            : silent mode: no output (except warnings and errors)", true)
		printOut("-overwrite         : overwrite the output file if it already exists", true)
		printOut("-input=<filename>  : mandatory name of the input file to encode", true)
		printOut("-output=<filename> : optional name of the output file (defaults to <input.knz>)", true)
		printOut("-block=<size>      : size of the block (max 16 MB / default 100 KB)", true)
		printOut("-entropy=          : Entropy codec to use [None|Huffman|Range|PAQ|FPAQ]", true)
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
		*outputName = *inputName + ".knz"
	}

	if *blockSize < 256 {
		fmt.Printf("The minimum block size is 256, the provided value is %d\n", *blockSize)
		os.Exit(ERR_BLOCK_SIZE)
	} else if *blockSize > 16*1024*1024-7 {
		fmt.Printf("The maximum block size is 16777209, the provided value is  %d\n", *blockSize)
		os.Exit(ERR_BLOCK_SIZE)
	}

	*entropy = strings.ToUpper(*entropy)

	switch *entropy {

	case "NONE":
		this.entropyType = 'N'

	case "HUFFMAN":
		this.entropyType = 'H'

	case "RANGE":
		this.entropyType = 'R'

	case "FPAQ":
		this.entropyType = 'F'

	case "PAQ":
		this.entropyType = 'P'

	default:
		fmt.Printf("Invalid entropy codec provided: %s\n", *entropy)
		os.Exit(ERR_INVALID_CODEC)
	}

	this.debug = *debug
	this.silent = *silent
	this.overwrite = *overwrite
	this.inputName = *inputName
	this.outputName = *outputName
	this.blockSize = *blockSize
	var err error
	this.blockCodec, err = function.NewBlockCodec(uint(this.blockSize))
	return this, err
}

func main() {
	bc, err := NewBlockCompressor()

	if err != nil {
		fmt.Printf("Failed to create block compressor: %v\n", err)
		os.Exit(ERR_CREATE_COMPRESSOR)
	}

	code, _ := bc.call()
	os.Exit(code)
}

// Return exit code, number of bits written
func (this *BlockCompressor) call() (int, uint64) {
	var msg string
	printOut("Input file name set to '"+this.inputName+"'", this.debug)
	printOut("Output file name set to '"+this.outputName+"'", this.debug)
	msg = fmt.Sprintf("Block size set to %d", this.blockSize)
	printOut(msg, this.debug)
	msg = fmt.Sprintf("Debug set to %t", this.debug)
	printOut(msg, this.debug)
	msg = fmt.Sprintf("Ouput file overwrite set to %t", this.overwrite)
	printOut(msg, this.debug)

	output, err := os.OpenFile(this.outputName, os.O_RDWR, 666)
	written := uint64(0)

	if err == nil {
		// File exists
		if this.overwrite == false {
			fmt.Print("The output file exists and the 'overwrite' command ")
			fmt.Println("line option has not been provided")
			output.Close()
			return ERR_OVERWRITE_FILE, written
		}
	} else {
		// File does not exist, create
		output, err = os.Create(this.outputName)

		if err != nil {
			fmt.Printf("Cannot open output file '%v' for writing: %v\n", this.outputName, err)
			return ERR_CREATE_FILE, written
		}
	}

	defer output.Close()

	if this.entropyType == 'H' {
		printOut("Using Huffman entropy codec", this.debug)
	} else if this.entropyType == 'R' {
		printOut("Using Range entropy codec", this.debug)
	} else if this.entropyType == 'P' {
		printOut("Using PAQ entropy codec", this.debug)
	} else if this.entropyType == 'F' {
		printOut("Using FPAQ entropy codec", this.debug)
	} else {
		printOut("Using no entropy codec", this.debug)
	}

	obs, err := bitstream.NewDefaultOutputBitStream(output)

	if err != nil {
		fmt.Printf("Cannot create output bit stream: %v\n", err)
		return ERR_CREATE_BITSTREAM, written
	}

	// Encode
	var entropyCoder kanzi.EntropyEncoder
	input, err := os.Open(this.inputName)

	if err != nil {
		fmt.Printf("Cannot open input file '%v': %v\n", this.inputName, err)
		return ERR_OPEN_FILE, written
	}

	defer input.Close()
	delta := int64(0)
	len := 0
	read := int64(0)
	step := 0
	printOut("Encoding ...", !this.silent)

	// Write header
	_, err = obs.WriteBits(BITSTREAM_TYPE, 24)
	
	if err == nil {
 		fmt.Printf("Cannot write header to output file '%v': %v\n", this.outputName, err)
		return ERR_WRITE_FILE, written	
	} else {
	   // Assume success for other calls once the first value has been written
	   obs.WriteBits(BITSTREAM_FORMAT_VERSION, 8)
	   obs.WriteBits(uint64(this.entropyType), 8)
	   obs.WriteBits(uint64(this.blockSize), 24)
	}
	
	written = obs.Written()

	// If the compression ratio is greater than one for this block,
	// the compression will fail (unless up to MAX_BLOCK_HEADER_SIZE bytes are reserved
	// in the block for header data)
	buffer := make([]byte, this.blockSize+MAX_BLOCK_HEADER_SIZE)
	len, err = input.Read(buffer[0:this.blockSize])

	for len > 0 {
		if err != nil {
			fmt.Printf("Failed to read the next chunk of input file '%v': %v\n", this.inputName, err)
			return ERR_READ_FILE, written
		}

		// Each block is encoded separately
		// Rebuild the entropy encoder to reset block statistics
		switch this.entropyType {
		case 'H':
			entropyCoder, err = entropy.NewHuffmanEncoder(obs)

		case 'R':
			entropyCoder, err = entropy.NewRangeEncoder(obs)

		case 'P':
			predictor, _ := entropy.NewPAQPredictor()
			entropyCoder, err = entropy.NewBinaryEntropyEncoder(obs, predictor)

		case 'F':
			predictor, _ := entropy.NewFPAQPredictor()
			entropyCoder, err = entropy.NewFPAQEntropyEncoder(obs, predictor)

		case 'N':
			if entropyCoder == nil {
				entropyCoder, err = entropy.NewNullEntropyEncoder(obs)
			}

		default:
			fmt.Printf("Invalid entropy encoder: %c\n", this.entropyType)
			return ERR_INVALID_CODEC, written
		}

		if err != nil {
			fmt.Printf("Failed to create entropy encoder: %v\n", err)
			return ERR_CREATE_CODEC, written
		}

		read += int64(len)
		before := time.Now()
		this.blockCodec.SetSize(uint(len))
		encoded, err := this.blockCodec.Encode(buffer, entropyCoder)
		after := time.Now()
		delta += after.Sub(before).Nanoseconds()

		if encoded < 0 || err != nil {
			if err != nil {
				fmt.Printf("Error in block codec forward(): %v\n", err)
			} else {
				fmt.Println("Error in block codec forward()")
			}

			return ERR_PROCESS_BLOCK, written
		}

		// Display the block size before and after block transform + entropy coding
		msg = fmt.Sprintf("Block %d: %d bytes (%d%%)", step, (obs.Written()-written)/8,
			(obs.Written()-written)*100/uint64(len*8))
		printOut(msg, this.debug)

		written = obs.Written()
		entropyCoder.Dispose()
		step++
		len, err = input.Read(buffer[0:this.blockSize])
	}

	// End block of size 0
	// The 'real' value is BlockCodec.COPY_BLOCK_MASK | (0 & BlockCodec.COPY_LENGTH_MASK)
	obs.WriteBits(0x80, 8)
	obs.Close()

	if read == 0 {
		fmt.Println("Empty input file ... nothing to do")
		return WARN_EMPTY_INPUT, written
	}

	delta /= 1000000 // convert to ms
	printOut("", !this.silent)
	msg = fmt.Sprintf("File size:        %d", read)
	printOut(msg, !this.silent)
	msg = fmt.Sprintf("Encoding took %d ms", delta)
	printOut(msg, !this.silent)
	msg = fmt.Sprintf("Ratio:            %f", float64(obs.Written())/float64(8*read))
	printOut(msg, !this.silent)
	msg = fmt.Sprintf("Encoded:          %d", obs.Written()/8)
	printOut(msg, !this.silent)
	msg = fmt.Sprintf("Troughput (KB/s): %d", ((read*int64(1000))>>10)/delta)
	printOut(msg, !this.silent)
	printOut("", !this.silent)
	return 0, obs.Written()
}

func printOut(msg string, print bool) {
	if print == true {
		fmt.Println(msg)
	}
}
