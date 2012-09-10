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



// Port of fpaq1 - Simple (and fast) adaptive order 0 entropy coder predictor
// ! Requires the coder to extend input bytes to 9-bit symbols !
public class FPAQPredictor implements Predictor
{
   private final short[][] states;
   private int ctxIdx;
   
   
   public FPAQPredictor()
   {
      this.ctxIdx = 1;
      this.states = new short[512][];
      
      for (int i=this.states.length-1; i>=0; i--)
        this.states[i] = new short[2];      
   }
   
   
   @Override
   public void update(int bit)
   {
      final short[] st = this.states[this.ctxIdx];
    
      if (++st[bit] > 2000) 
      {
         st[0] >>= 1;
         st[1] >>= 1;
      }
      
      this.ctxIdx <<= 1;
      this.ctxIdx += bit;
      
      if (this.ctxIdx >= 512)
        this.ctxIdx = 1;  
   }

   
   // Assume stream of 9-bit symbols   
   // ! Requires the coder to extend input bytes to 9-bit symbols !
   @Override
   public int get()
   {
      final short[] st = this.states[this.ctxIdx];
      return ((st[1]+1) << 12) / (st[0]+st[1]+2);      
   }
}   