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

package kanzi.test;

import kanzi.VideoEffect;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import kanzi.filter.SobelFilter;


public class TestEffects
{
    public static void main(String[] args)
    {
        try
        {
            String fileName = (args.length > 0) ? args[0] : "c:\\temp\\lena.jpg";
            ImageIcon icon = new ImageIcon(fileName);
            Image image = icon.getImage();
            int w = image.getWidth(null);
            int h = image.getHeight(null);
            System.out.println(w+"x"+h);
            GraphicsDevice gs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0];
            GraphicsConfiguration gc = gs.getDefaultConfiguration();
            BufferedImage img = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            img.getGraphics().drawImage(image, 0, 0, null);
            BufferedImage img2 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            BufferedImage img3 = null;
            int[] source = new int[w*h];
            int[] dest = new int[w*h];
            int[] tmp = new int[w*h];

            // Do NOT use img.getRGB(): it is more than 10 times slower than
            // img.getRaster().getDataElements()
            img.getRaster().getDataElements(0, 0, w, h, source);

            VideoEffect effect;
            //effect = new GlowingEffect(200, h, 100, w, 20, 10);
            effect = new SobelFilter(w, h);
            //effect = new BilateralFilter(w, h, 0, w, 3, 8);
            effect.apply(source, dest);
            System.arraycopy(dest, 0, tmp, 0, w * h);

            // Calculate image difference
            boolean imgDiff = false;

            if (imgDiff == true)
            {
               int[] delta = new int[w*h];
               img3 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);

               for (int j = 0; j < h; j++)
               {
                  for (int i = 0; i < w; i++)
                  {
                     int p1 = source[j * w + i];
                     int p2 = dest[j * w + i];
                     int r1 = (p1 >> 16) & 0xFF;
                     int g1 = (p1 >> 8) & 0xFF;
                     int b1 = p1 & 0xFF;
                     int r2 = (p2 >> 16) & 0xFF;
                     int g2 = (p2 >> 8) & 0xFF;
                     int b2 = p2 & 0xFF;
                     int r = Math.abs(r1 - r2) & 0xFF;
                     int g = Math.abs(g1 - g2) & 0xFF;
                     int b = Math.abs(b1 - b2) & 0xFF;
                     int avg = (r + g + b) / 3;
                     avg <<= 5; // magnify small errors

                     if (avg > 255)
                        avg = 255;

                     delta[j * w + i] = (avg << 16) | (avg << 8) | avg;
                  }
               }

               img3.getRaster().setDataElements(0, 0, w, h, delta);
            }

            img2.getRaster().setDataElements(0, 0, w, h, dest);

            //icon = new ImageIcon(img);
            JFrame frame = new JFrame("Original");
            frame.setBounds(150, 100, w, h);
            frame.add(new JLabel(icon));
            frame.setVisible(true);
            JFrame frame2 = new JFrame("Filter");
            frame2.setBounds(700, 150, w, h);
            ImageIcon newIcon = new ImageIcon(img2);
            frame2.add(new JLabel(newIcon));
            frame2.setVisible(true);

            if (imgDiff == true)
            {
               ImageIcon icon3 = new ImageIcon(img3);
               JFrame frame3 = new JFrame("Delta");
               frame3.setBounds(200, 100, w, h);
               frame3.add(new JLabel(icon3));
               frame3.setVisible(true);
            }

            // Speed test
            {
                System.arraycopy(source, 0, tmp, 0, w * h);
                System.out.println("Speed test");
                int iters = 1000;
                long before = System.nanoTime();

                for (int ii=0; ii<iters; ii++)
                {
                   effect.apply(source, tmp);
                }

                long after = System.nanoTime();
                System.out.println("Elapsed [ms]: "+ (after-before)/1000000+" ("+iters+" iterations)");
            }

            try
            {
                Thread.sleep(45000);
            }
            catch (Exception e)
            {
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        System.exit(0);
    }
}
