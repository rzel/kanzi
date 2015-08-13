#Complete Silesia compression performance results

# Silesia corpus compression tests #

Compression results for the Silesia Corpus (http://sun.aei.polsl.pl/~sdeor/index.php?page=silesia)

| Encoder/Decoder | Compression | Compression |  | Decompression |  | Compression |
|:----------------|:------------|:------------|:-|:--------------|:-|:------------|
| Version         | Type        | Time (ms)   | Throughput (KB/s) | Time (ms)     | Throughput (KB/s) | Ratio       |
| Java 10/14      | RANGE       | 25950       | 7976 | 13919         | 14870 | 24.89%      |
| Java 7/14       | RANGE       | 30487       | 6789 | 18139         | 11410 | 25.67%      |
| Java 1/14       | RANGE       | 35761       | 5788 | 18298         | 11311 | 25.67%      |
| Java 9/13       | RANGE       | 36172       | 5722 | 19310         | 10718 | 25.67%      |
| Java 3/13       | RANGE       | 36776       | 5628 | 22334         | 9267 | 25.67%      |
| Java 10/14+flags | RANGE       | 25332       | 8170 | 13837         | 14958 | 24.89%      |
| Java 7/14+flags | RANGE       | 29888       | 6925 | 18285         | 11319 | 25.67%      |
| Java 1/14+flags | RANGE       | 33543       | 6170 | 17987         | 11507 | 25.67%      |
| Java 9/13+flags | RANGE       | 34511       | 5997 | 19307         | 10720 | 25.67%      |
| Go 10/14        | RANGE       | 28434       | 7279 | 15586         | 13279 | 24.89%      |
| Go 7/14         | RANGE       | 34692       | 5966 | 23453         | 8825 | 25.67%      |
| Go 1/14         | RANGE       | 41637       | 4971 | 23494         | 8810 | 25.67%      |
| Go 9/13         | RANGE       | 41733       | 4959 | 23942         | 8645 | 25.67%      |
| Java 10/14      | ANS         | 26740       | 7740 | 11875         | 17429 | 25.62%      |
| Java 10/14+flags | ANS         | 25810       | 8019 | 11812         | 17522 | 25.62%      |
| Go 10/14        | ANS         | 29272       | 7071 | 13939         | 14848 | 25.62%      |
| Java 10/14      | PAQ         | 48884       | 4234 | 36038         | 5743 | 23.90%      |
| Java 7/14       | PAQ         | 60936       | 3397 | 47352         | 4371 | 23.98%      |
| Java 1/14       | PAQ         | 69320       | 2986 | 47518         | 4356 | 23.98%      |
| Java 9/13       | PAQ         | 68650       | 3015 | 47779         | 4332 | 23.98%      |
| Java 3/13       | PAQ         | 68791       | 3009 | 54345         | 3808 | 23.99%      |
| Java 10/14+flags | PAQ         | 47519       | 4356 | 35481         | 5833 | 23.90%      |
| Java 7/14+flags | PAQ         | 54088       | 3827 | 41129         | 5032 | 23.98%      |
| Java 1/14+flags | PAQ         | 55635       | 3720 | 41337         | 5007 | 23.98%      |
| Java 9/13+flags | PAQ         | 58649       | 3529 | 41597         | 4976 | 23.98%      |
| Go 10/14        | PAQ         | 77377       | 2675 | 66088         | 3132 | 23.90%      |
| Go 7/14         | PAQ         | 79151       | 2615 | 66612         | 3107 | 23.98%      |
| Go 1/14         | PAQ         | 86720       | 2387 | 67399         | 3071 | 23.98%      |
| Go 9/13         | PAQ         | 87073       | 2377 | 67328         | 3074 | 23.98%      |
| Java 10/14      | HUFFMAN     | 25202       | 8212 | 11788         | 17558 | 25.75%      |
| Java 7/14       | HUFFMAN     | 27676       | 7478 | 13315         | 15544 | 25.75%      |
| Java 1/14       | HUFFMAN     | 32747       | 6320 | 13463         | 15373 | 25.75%      |
| Java 9/13       | HUFFMAN     | 33563       | 6167 | 15273         | 13551 | 25.75%      |
| Java 3/13       | HUFFMAN     | 34181       | 6055 | 18289         | 11317 | 26.66%      |
| Java 10/14+flags | HUFFMAN     | 24554       | 8429 | 11737         | 17634 | 25.75%      |
| Java 7/14+flags | HUFFMAN     | 26958       | 7678 | 13423         | 15419 | 25.75%      |
| Java 1/14+flags | HUFFMAN     | 30895       | 6699 | 13559         | 15264 | 25.75%      |
| Java 9/13+flags | HUFFMAN     | 31934       | 6481 | 15424         | 13419 | 25.75%      |
| Go 10/14        | HUFFMAN     | 28089       | 7368 | 13565         | 15258 | 25.75%      |
| Go 7/14         | HUFFMAN     | 29315       | 7060 | 15913         | 13006 | 25.75%      |
| Go 1/14         | HUFFMAN     | 36685       | 5642 | 16331         | 12674 | 25.75%      |
| Go 9/13         | HUFFMAN     | 37504       | 5519 | 18537         | 11165 | 25.75%      |
| Java 10/14      | FPAQ        | 31981       | 6472 | 20457         | 10117 | 24.51%      |
| Java 7/14       | FPAQ        | 35036       | 5907 | 21703         | 9537 | 24.51%      |
| Java 1/14       | FPAQ        | 40028       | 5171 | 22721         | 9109 | 24.51%      |
| Java 9/13       | FPAQ        | 39338       | 5261 | 22360         | 9256 | 24.51%      |
| Java 3/13       | FPAQ        | 41416       | 4997 | 26551         | 7795 | 25.00%      |
| Java 10/14+flags | FPAQ        | 31540       | 6562 | 20463         | 10114 | 24.51%      |
| Java 7/14+flags | FPAQ        | 34077       | 6074 | 21748         | 9517 | 24.51%      |
| Java 1/14+flags | FPAQ        | 37369       | 5539 | 21797         | 9495 | 24.51%      |
| Java 9/13+flags | FPAQ        | 37605       | 5504 | 22161         | 9339 | 24.51%      |
| Go 10/14        | FPAQ        | 46306       | 4470 | 34000         | 6087 | 24.51%      |
| Go 7/14         | FPAQ        | 48471       | 4270 | 35478         | 5834 | 24.51%      |
| Go 1/14         | FPAQ        | 55906       | 3702 | 36261         | 5708 | 24.51%      |
| Go 9/13         | FPAQ        | 57075       | 3626 | 38491         | 5377 | 24.51%      |

The tests were performed on a desktop i7-2600 @3.40GHz, Win7, 16GB RAM with Oracle JDK7 (1.7.0\_40) for Kanzi 3/13, 9/13, 1/14, Oracle JDK7 (1.7.0\_45) for 7/14 and Oracle JDK8 (1.8.0\_20) for 10/14.

Kanzi 9/13 running with Go 1.1, Kanzi 1/14 running with Go 1.2 and Kanzi 7/14 running with Go 1.3.


Average of median 3 (of 5) tests used.

The BWT+MTF transform was used for all tests. No checksum.

Java optimized flags: -Xms1024M -XX:-UseCompressedOops -XX:+UseTLAB -XX:+AggressiveOpts -XX:+UseFastAccessorMethods

The block size was set arbitrarily to 4000000 bytes. Bigger sizes yield better compression ratios and smaller sizes yield better speed.

The compression ratio is the size of the compressed file divided by the size of the original size.
