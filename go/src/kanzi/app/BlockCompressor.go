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
	"flag"
	"fmt"
	"kanzi/io"
	"os"
	"strings"
	"time"
)

const (
	DEFAULT_BUFFER_SIZE = 32768
	WARN_EMPTY_INPUT    = -128
)

type BlockCompressor struct {
	verbose      bool
	silent       bool
	overwrite    bool
	checksum     bool
	inputName    string
	outputName   string
	entropyCodec string
	transform    string
	blockSize    uint
}

func NewBlockCompressor() (*BlockCompressor, error) {
	this := new(BlockCompressor)

	// Define flags
	var help = flag.Bool("help", false, "display the help message")
	var verbose = flag.Bool("verbose", false, "display the size of the block at each stage (in bytes, floor rounding if fractional)")
	var silent = flag.Bool("silent", false, "silent mode: no output (except warnings and errors)")
	var overwrite = flag.Bool("overwrite", false, "overwrite the output file if it already exists")
	var inputName = flag.String("input", "", "mandatory name of the input file to encode")
	var outputName = flag.String("output", "", "optional name of the output file (defaults to <input.knz>)")
	var blockSize = flag.Int("block", 100000, "size of the blocks (max 16 MB / min 1KB / default 100 KB)")
	var entropy = flag.String("entropy", "Huffman", "entropy codec to use [None|Huffman*|Range|PAQ|FPAQ]")
	var function = flag.String("transform", "Block", "transform to use [None|Block*|Snappy|LZ4|RLT]")
	var cksum = flag.Bool("checksum", false, "enable block checksum")

	// Parse
	flag.Parse()

	if *help == true {
		printOut("-help              : display this message", true)
		printOut("-verbose           : display the size of the block at each stage (in bytes, floor rounding if fractional)", true)
		printOut("-silent            : silent mode: no output (except warnings and errors)", true)
		printOut("-overwrite         : overwrite the output file if it already exists", true)
		printOut("-input=<filename>  : mandatory name of the input file to encode", true)
		printOut("-output=<filename> : optional name of the output file (defaults to <input.knz>)", true)
		printOut("-block=<size>      : size of the blocks (max 16 MB / min 1KB / default 100 KB)", true)
		printOut("-entropy=          : entropy codec to use [None|Huffman*|Range|PAQ|FPAQ]", true)
		printOut("-transform=        : transform to use [None|Block*|Snappy|LZ4|RLT]", true)
		printOut("-checksum          : enable block checksum", true)
		os.Exit(0)
	}

	if *silent == true && *verbose == true {
		printOut("Warning: both 'silent' and 'verbose' options were selected, ignoring 'verbose'", true)
		*verbose = false
	}

	if len(*inputName) == 0 {
		fmt.Printf("Missing input file name, exiting ...\n")
		os.Exit(io.ERR_MISSING_FILENAME)
	}

	if len(*outputName) == 0 {
		*outputName = *inputName + ".knz"
	}

	this.verbose = *verbose
	this.silent = *silent
	this.overwrite = *overwrite
	this.inputName = *inputName
	this.outputName = *outputName
	this.blockSize = uint(*blockSize)
	this.entropyCodec = strings.ToUpper(*entropy)
	this.transform = strings.ToUpper(*function)
	this.checksum = *cksum
	return this, nil
}

func main() {
	bc, err := NewBlockCompressor()

	if err != nil {
		fmt.Printf("Failed to create block compressor: %v\n", err)
		os.Exit(io.ERR_CREATE_COMPRESSOR)
	}

	defer func() {
		if r := recover(); r != nil {
			fmt.Printf("An unexpected error occured during compression: %v\n", r.(error))
			os.Exit(io.ERR_UNKNOWN)
		}
	}()

	code, _ := bc.call()
	os.Exit(code)
}

// Return exit code, number of bits written
func (this *BlockCompressor) call() (int, uint64) {
	var msg string
	printOut("Input file name set to '"+this.inputName+"'", this.verbose)
	printOut("Output file name set to '"+this.outputName+"'", this.verbose)
	msg = fmt.Sprintf("Block size set to %d", this.blockSize)
	printOut(msg, this.verbose)
	msg = fmt.Sprintf("Debug set to %t", this.verbose)
	printOut(msg, this.verbose)
	msg = fmt.Sprintf("Overwrite set to %t", this.overwrite)
	printOut(msg, this.verbose)
	msg = fmt.Sprintf("Checksum set to %t", this.checksum)
	printOut(msg, this.verbose)
	w1 := "no"

	if this.transform != "NONE" {
		w1 = this.transform
	}

	msg = fmt.Sprintf("Using %s transform (stage 1)", w1)
	printOut(msg, this.verbose)
	w2 := "no"

	if this.entropyCodec != "NONE" {
		w2 = this.entropyCodec
	}

	msg = fmt.Sprintf("Using %s entropy codec (stage 2)", w2)
	printOut(msg, this.verbose)
	written := uint64(0)
	output, err := os.OpenFile(this.outputName, os.O_RDWR, 666)

	if err == nil {
		// File exists
		if this.overwrite == false {
			fmt.Print("The output file exists and the 'overwrite' command ")
			fmt.Println("line option has not been provided")
			output.Close()
			return io.ERR_OVERWRITE_FILE, written
		}
	} else {
		// File does not exist, create
		output, err = os.Create(this.outputName)

		if err != nil {
			fmt.Printf("Cannot open output file '%v' for writing: %v\n", this.outputName, err)
			return io.ERR_CREATE_FILE, written
		}
	}

	defer output.Close()
	verboseWriter := os.Stdout

	if this.verbose == false {
		verboseWriter = nil
	}

	cos, err := io.NewCompressedOutputStream(this.entropyCodec, this.transform,
		output, this.blockSize, this.checksum, verboseWriter)

	if err != nil {
		if ioerr, isIOErr := err.(io.IOError); isIOErr == true {
			fmt.Printf("%s\n", ioerr.Error())
			return ioerr.ErrorCode(), written
		} else {
			fmt.Printf("Cannot create compressed stream: %s\n", err.Error())
			return io.ERR_CREATE_COMPRESSOR, written
		}
	}

	defer cos.Close()
	input, err := os.Open(this.inputName)

	if err != nil {
		fmt.Printf("Cannot open input file '%v': %v\n", this.inputName, err)
		return io.ERR_OPEN_FILE, written
	}

	defer input.Close()

	// Encode
	len := 0
	read := int64(0)
	printOut("Encoding ...", !this.silent)
	written = cos.GetWritten()
	buffer := make([]byte, DEFAULT_BUFFER_SIZE)
	before := time.Now()
	len, err = input.Read(buffer)

	for len > 0 {
		if err != nil {
			fmt.Printf("Failed to read the next chunk of input file '%v': %v\n", this.inputName, err)
			return io.ERR_READ_FILE, written
		}

		read += int64(len)

		if _, err = cos.Write(buffer[0:len]); err != nil {
			if ioerr, isIOErr := err.(io.IOError); isIOErr == true {
				fmt.Printf("%s\n", ioerr.Error())
				return ioerr.ErrorCode(), written
			} else {
				fmt.Printf("Error in block codec forward(): %v\n", err.Error())
				return io.ERR_PROCESS_BLOCK, written
			}
		}

		len, err = input.Read(buffer)
	}

	if read == 0 {
		fmt.Println("Empty input file ... nothing to do")
		return WARN_EMPTY_INPUT, written
	}

	// Close streams to ensure all data are flushed
	// Deferred close is fallback for error paths
	cos.Close()

	after := time.Now()
	delta := after.Sub(before).Nanoseconds() / 1000000 // convert to ms

	printOut("", !this.silent)
	msg = fmt.Sprintf("Encoding:          %d ms", delta)
	printOut(msg, !this.silent)
	msg = fmt.Sprintf("Input size:        %d", read)
	printOut(msg, !this.silent)
	msg = fmt.Sprintf("Output size:       %d", cos.GetWritten())
	printOut(msg, !this.silent)
	msg = fmt.Sprintf("Ratio:             %f", float64(cos.GetWritten())/float64(read))
	printOut(msg, !this.silent)

	if delta > 0 {
		msg = fmt.Sprintf("Throughput (KB/s): %d", ((read*int64(1000))>>10)/delta)
		printOut(msg, !this.silent)
	}

	printOut("", !this.silent)
	return 0, cos.GetWritten()
}

func printOut(msg string, print bool) {
	if print == true {
		fmt.Println(msg)
	}
}
