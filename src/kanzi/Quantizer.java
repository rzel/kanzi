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

package kanzi;


public interface Quantizer
{
    public int[] quantize(int[] input, int offset, int len);
    
    // Get the offset that minimizes the quantization error (in 1/16th of step)
    public int getBias();

    // Reset the bias. Does nothing if the bias is not dynamically computed 
    public void resetBias();
}
