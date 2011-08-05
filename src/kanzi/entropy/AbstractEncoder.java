/*
Copyright 2011 Frederic Langlet
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

package kanzi.entropy;

import kanzi.EntropyEncoder;
import kanzi.bitstream.BitStream;
import kanzi.bitstream.BitStreamException;


public abstract class AbstractEncoder implements EntropyEncoder
{

    public abstract boolean encodeByte(byte val);

    public abstract BitStream getBitStream();

    // Default implementation: fallback to encodeByte
    // Some implementations should be able to use an optimized algorithm
    public int encode(byte[] array, int blkptr, int len)
    {
        if ((array == null) || (blkptr + len > array.length) || (blkptr < 0) || (len < 0))
           return -1;

        int end = blkptr + len;
        int i = blkptr;

        try
        {
           while (i<end)
           {
              if (this.encodeByte(array[i]) == false)
                 return i;

              i++;
           }
        }
        catch (BitStreamException e)
        {
           return i;
        }

        return len;
    }


    public void dispose()
    {
    }

}
