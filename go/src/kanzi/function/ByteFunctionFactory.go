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

package function

import (
	"fmt"
	"kanzi"
	"kanzi/transform"
	"strings"
)

const (
	// Transform: 4 lsb
	NULL_TRANSFORM_TYPE = byte(0)
	BWT_TYPE            = byte(1)
	BWTS_TYPE           = byte(2)
	LZ4_TYPE            = byte(3)
	SNAPPY_TYPE         = byte(4)
	RLT_TYPE            = byte(5)

	// GST: 3 msb
)

func NewByteFunction(size uint, functionType byte) (kanzi.ByteFunction, error) {
	switch functionType & 0x0F {

	case SNAPPY_TYPE:
		return NewSnappyCodec(size)

	case LZ4_TYPE:
		return NewLZ4Codec(size)

	case RLT_TYPE:
		return NewRLT(size, 3)

	case BWT_TYPE:
		bwt, err := transform.NewBWT(size)

		if err != nil {
			return nil, err
		}

		return NewBWTBlockCodec(bwt, int(functionType)>>4, size) // raw BWT

	case BWTS_TYPE:
		bwts, err := transform.NewBWTS(size)

		if err != nil {
			return nil, err
		}

		return NewBWTBlockCodec(bwts, int(functionType)>>4, size) // raw BWTS

	case NULL_TRANSFORM_TYPE:
		return NewNullFunction(size)

	default:
		return nil, fmt.Errorf("Unsupported function type: '%c'", functionType)
	}
}

func getGSTType(args string) byte {
	switch strings.ToUpper(args) {
	case "MTF":
		return GST_MODE_MTF

	case "RANK":
		return GST_MODE_RANK

	case "TIMESTAMP":
		return GST_MODE_TIMESTAMP

	case "":
		return GST_MODE_RAW

	case "NONE":
		return GST_MODE_RAW

	default:
		panic(fmt.Errorf("Unknown GST type: '%v'", args))
	}
}

func getGSTName(gstType int) string {
	switch gstType {
	case GST_MODE_MTF:
		return "MTF"

	case GST_MODE_RANK:
		return "RANK"

	case GST_MODE_TIMESTAMP:
		return "TIMESTAMP"

	case GST_MODE_RAW:
		return ""

	default:
		panic(fmt.Errorf("Unknown GST type: '%v'", gstType))
	}
}

func GetByteFunctionName(functionType byte) string {
	switch byte(functionType & 0x0F) {

	case SNAPPY_TYPE:
		return "SNAPPY"

	case LZ4_TYPE:
		return "LZ4"

	case RLT_TYPE:
		return "RLT"

	case BWT_TYPE:
		gstName := getGSTName(int(functionType) >> 4)

		if len(gstName) == 0 {
			return "BWT"
		} else {
			return "BWT+" + gstName
		}

	case BWTS_TYPE:
		gstName := getGSTName(int(functionType) >> 4)

		if len(gstName) == 0 {
			return "BWTS"
		} else {
			return "BWTS+" + gstName
		}

	case NULL_TRANSFORM_TYPE:
		return "NONE"

	default:
		panic(fmt.Errorf("Unsupported function type: '%c'", functionType))
	}
}

func GetByteFunctionType(functionName string) byte {
	args := ""
	functionName = strings.ToUpper(functionName)

	if strings.HasPrefix(functionName, "BWT") {
		tokens := strings.Split(functionName, "+")

		if len(tokens) > 1 {
			functionName = tokens[0]
			args = tokens[1]
		}
	}

	switch functionName {

	case "SNAPPY":
		return SNAPPY_TYPE

	case "LZ4":
		return LZ4_TYPE

	case "RLT":
		return RLT_TYPE

	case "BWT":
		gst := getGSTType(args)
		return byte((gst << 4) | BWT_TYPE)

	case "BWTS":
		gst := getGSTType(args)
		return byte((gst << 4) | BWTS_TYPE)

	case "NONE":
		return NULL_TRANSFORM_TYPE

	default:
		panic(fmt.Errorf("Unsupported function type: '%s'", functionName))
	}
}
