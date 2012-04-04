/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package kanzi.test;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.util.TreeSet;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import kanzi.util.QuadTreeGenerator;


/**
 *
 * @author fred
 */
public class TestQuadTreeGenerator
{
  
    public static void main(String[] args)
    {
        try
        {
            String fileName = (args.length > 0) ? args[0] : "c:\\temp\\lena.jpg";
            ImageIcon icon = new ImageIcon(fileName);
            Image image = icon.getImage();
            int w = image.getWidth(null) & -7;
            int h = image.getHeight(null) & -7;
            System.out.println(w+"x"+h);
            GraphicsDevice gs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0];
            GraphicsConfiguration gc = gs.getDefaultConfiguration();
            BufferedImage img = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            img.getGraphics().drawImage(image, 0, 0, null);
            BufferedImage img2 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            int[] source = new int[w*h];
            int[] dest = new int[w*h];
            TreeSet<QuadTreeGenerator.Node> nodes = new TreeSet<QuadTreeGenerator.Node>();            

            // Do NOT use img.getRGB(): it is more than 10 times slower than
            // img.getRaster().getDataElements()
            img.getRaster().getDataElements(0, 0, w, h, source);
            System.arraycopy(source, 0, dest, 0, w*h);
            img2.getRaster().setDataElements(0, 0, w, h, dest);

            int nbNodes = 800;
            int minNodeDim = 8;
            nodes.clear();
            new QuadTreeGenerator(w, h, nbNodes, minNodeDim).decompose(nodes, source);
            
            for (QuadTreeGenerator.Node node : nodes)
               img2.getGraphics().drawRect(node.x, node.y, node.w, node.h);

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

