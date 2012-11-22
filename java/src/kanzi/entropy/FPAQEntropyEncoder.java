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
package kanzi.entropy;

import kanzi.EntropyEncoder;
import kanzi.OutputBitStream;


// Based on fpaq1 by Matt Mahoney - Stationary order 0 entropy encoder 
public class FPAQEntropyEncoder extends BinaryEntropyEncoder implements EntropyEncoder
{
   public FPAQEntropyEncoder(OutputBitStream obs, Predictor p)
   {
      super(obs, p);
   }
   
   
   @Override
   public boolean encodeByte(byte val)
   {
      this.encodeBit(0);
      return super.encodeByte(val);
   }

   
   @Override
   public void dispose()
   {
      this.encodeBit(1);
      super.flush();
      this.getBitStream().writeBits(0, 24);
      this.getBitStream().flush();
   }                     
   
}
