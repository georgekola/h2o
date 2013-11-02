package samples;

import water.*;
import water.deploy.*;
import water.util.Utils;

public class CloudLocal {
  /**
   * Launches a 1 node cluster. You might want to increase the JVM heap, e.g. -Xmx12G.
   */
  public static void main(String[] args) throws Exception {
    launch(1, null);
  }

  /**
   * Launches a local multi-nodes cluster by spawning additional JVMs. JVM parameters and classpath
   * are replicated from the current one.
   */
  public static void launch(int nodes, Class<? extends Job> job) throws Exception {
    // Additional logging info
    System.setProperty("h2o.debug", "true");
    Boot.main(UserCode.class, new String[] { "" + nodes, job != null ? job.getName() : "null" });
  }

  public static class UserCode {
    public static void userMain(String[] args) throws Exception {
      int nodes = Integer.parseInt(args[0]);
      String ip = "127.0.0.1";
      int port = 54321;
      // Flat file is not necessary, H2O can auto-discover nodes using multi-cast, added
      // here for increased determinism and as a way to get multiple clouds on same box
      String flat = "";
      for( int i = 0; i < nodes; i++ )
        flat += ip + ":" + (port + i * 2) + '\n';
      String flatfile = Utils.writeFile(flat).getAbsolutePath();
      for( int i = 1; i < nodes; i++ ) {
        String[] a = args(ip, (port + i * 2), flatfile);
        Node worker = new NodeVM(a);
        worker.inheritIO();
        worker.start();
      }
      H2O.main(args(ip, port, flatfile));
      TestUtil.stall_till_cloudsize(nodes);
      System.out.println("Cloud is up");
      System.out.println("Go to http://127.0.0.1:54321");

      if( !args[1].equals("null") ) {
        String pack = args[1].substring(0, args[1].lastIndexOf('.'));
        LaunchJar.weavePackages(pack);
        Class<Job> job = (Class) Class.forName(args[1]);
        job.newInstance().fork();
      }
    }
  }

  static String[] args(String ip, int port, String flatfile) {
    return new String[] { "-ip", ip, "-port", "" + port, "-flatfile", flatfile };
  }
}