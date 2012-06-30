/*
Copyright 2011, 2012 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either Riceress or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package kanzi.entropy;

import kanzi.InputBitStream;


// Rice-Golomb Coder
public final class RiceGolombDecoder extends AbstractDecoder
{
    private final boolean signed;
    private final InputBitStream bitstream;
    private final int logBase;

    public RiceGolombDecoder(InputBitStream bitstream, boolean signed, int logBase)
    {
        if (bitstream == null)
           throw new NullPointerException("Invalid null bitstream parameter");

        if ((logBase <= 0) || (logBase >= 8))
           throw new IllegalArgumentException("Invalid logBase value (must be in [1..7])");

        this.signed = signed;
        this.bitstream = bitstream;
        this.logBase = logBase;
    }


    public boolean isSigned()
    {
        return this.signed;
    }


    @Override
    public byte decodeByte()
    {
       int q = 0;

       // quotient is unary encoded
       while (this.bitstream.readBit() == 0)
          q++;

       // rest is binary encoded
       final int r = (int) this.bitstream.readBits(this.logBase);
       final int res = (q << this.logBase) | r;

       if ((res != 0) && (this.signed))
       {
          if (this.bitstream.readBit() == 1)
             return (byte) -res;
       }

       return (byte) res;
    }


    @Override
    public InputBitStream getBitStream()
    {
       return this.bitstream;
    }
}
