# Block Codec #

## Introduction ##


The BWT block codec compresses data in blocks of size between 1024 bytes and 32 MB (minus 4 for header). It is based on the Burrows-Wheeler transform to convert frequently recurring byte sequences into sequences of identical values. It then applies move-to-front transform and entropy coding.

The performance of the BWT block codec is asymmetric, decompression being roughly twice as fast as compression.

## Details ##

The block codec uses several compression techniques in a sequence:

1) Burrows-Wheeler transform (BWT): this is a reversible block-sort transform than does not modify the size of the input block (see http://en.wikipedia.org/wiki/Burrows%E2%80%93Wheeler_transform). The size of the block can be as high as 256 MB (minus header). The bigger the block, the higher the compression ratio (at the expense of compression time). A naive implementation of block sorting is not possible because the matrix to sort block permutations would be possibly gigantic (256MB\*256MB) and because sorting is very slow. Instead, a linear time suffix sorting algorithm called DivSufSort (implemented by Yuta Mori) is used.
This algorithm allows for fast creation of the block suffix array which can be easily transformed into the outcome of the BWT. The original code (https://code.google.com/p/libdivsufsort/) has been ported to Java and Go. It replaces the Suffix Array Induction sorting used previously (see http://sites.google.com/site/yuta256/sais). DivSufSort outperforms SA-IS in terms of computation speed by around 15% (typical observed speed up).

2) Move to front transform (MTFT): this transform does not modify the size of the processed block (see http://en.wikipedia.org/wiki/Move-to-front_transform). Each of the symbols is placed in an array. When a symbol is processed, it is replaced by its location (index) in the array and that symbol is shuffled to the front of the array. As the MTFT assigns low values to symbols that reappear frequently, this results in a data stream which contains a lot of symbols in the low integer range, many of them being identical. Such data can be very efficiently encoded. For speed purpose, the implementation uses lists for the forward transform anf array for the reverse transform. The MTFT cn be replaced by another transform such as the Distance Coding. Distance Coding produces a more compact output but is less amenable to entropy coding, resulting in a less compressed output overall.

3) Zero run length transform (ZRLT): long strings of repeated 0 in the output are replaced by a run-length. A run length is bit encoded (E.G. rl=0x0A is encoded as 0s and 1s ignoring the MSB which is always 1 => 010). Any other symbol is incremented by one and encoded as-is (except 254 and 255, which require 2 bytes). The decoder knows that 0 and 1 are special symbols encoding a run-length and that 0xFF is followed by an extra byte of data. The ZLRT results usually in a much smaller block due to the repetitions of 0 after the BWT. However if no compression is achieved, the ZLRT results are discarded and the block is directly copied to the output bit stream.

4) Entropy coding. The default entropy codec is Huffman based because it provides the best compression ratio devided by encoding time. Other algorithm are available: range codec (usually a bit better than Huffman but 20% slower) and a PAQ codec (context based bit arithmetic codec) which improves over Huffman in compression but doubles (or more) encoding time. The implementation of the Huffman algorithm is canonical (see http://en.wikipedia.org/wiki/Huffman_coding). Huffman tables (containing only symbol lengths) are delta encoded at the beginning of the block. Each bit-length is stored as an encoded difference against the previous code bit-length and unary encoded using Exp-Golomb coding (see http://en.wikipedia.org/wiki/Exponential-Golomb_coding). This table encoding provides a compact representation of the bit-lengths.

When Using PAQ as entropy coding, it is recommended to bypass MTFT and ZLRT and use a raw BWT as first stage since PAQ is able to retrieve patterns in the original BWT output (an order 0 transform like MTFT hurts compression in this case).

## Block size ##

Test the impact of the block size on the block codec compression ratio:

http://kanzi.googlecode.com/files/compression_ratio.pdf