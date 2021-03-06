package hex;

import hex.Layer.ChunkLinear;
import hex.Layer.ChunkSoftmax;
import hex.Layer.ChunksInput;
import hex.Layer.Input;
import hex.Layer.Linear;
import hex.Layer.Output;
import hex.Layer.Softmax;
import hex.Layer.VecLinear;
import hex.Layer.VecSoftmax;
import hex.Layer.VecsInput;
import jsr166y.CountedCompleter;
import water.*;
import water.H2O.H2OCountedCompleter;
import water.H2O.H2OEmptyCompleter;
import water.Job.ValidatedJob;
import water.api.*;
import water.fvec.*;
import water.util.RString;
import water.util.Utils;

/**
 * Neural network.
 *
 * @author cypof
 */
public class NeuralNet extends ValidatedJob {
  static final int API_WEAVER = 1;
  public static DocGen.FieldDoc[] DOC_FIELDS;
  public static final String DOC_GET = "Neural Network";
  public static final int EVAL_ROW_COUNT = 1000;

  public enum Activation {
    Tanh, Rectifier
  };

  @API(help = "Activation function", filter = Default.class)
  public Activation activation = Activation.Tanh;

  @API(help = "Hidden layer sizes, e.g. (1000, 1000)", filter = Default.class)
  public int[] hidden = new int[] { 500 };

  @API(help = "Learning rate", filter = Default.class)
  public double rate = .01;

  @API(help = "L2 regularization", filter = Default.class)
  public double l2 = .0001;

  @API(help = "How many times the dataset should be iterated", filter = Default.class)
  public int epochs = 100;

  public NeuralNet() {
    description = DOC_GET;
  }

  @Override public Job fork() {
    init();
    H2OCountedCompleter start = new H2OCountedCompleter() {
      @Override public void compute2() {
        startTrain();
        tryComplete();
      }

      @Override public boolean onExceptionalCompletion(Throwable ex, CountedCompleter caller) {
        Job.cancel(job_key, Utils.getStackAsString(ex));
        return super.onExceptionalCompletion(ex, caller);
      }
    };
    start(new H2OEmptyCompleter());
    H2O.submitTask(start);
    return this;
  }

  public void startTrain() {
    Vec[] vecs = Utils.append(_train, response);
    reChunk(vecs);
    final Vec[] train = new Vec[vecs.length - 1];
    System.arraycopy(vecs, 0, train, 0, train.length);
    final Vec trainResp = classification ? vecs[vecs.length - 1].toEnum() : vecs[vecs.length - 1];

    final Layer[] ls = new Layer[hidden.length + 2];
    ls[0] = new VecsInput(train, null);
    for( int i = 0; i < hidden.length; i++ ) {
      if( activation == Activation.Rectifier )
        ls[i + 1] = new Layer.Rectifier(hidden[i]);
      else
        ls[i + 1] = new Layer.Tanh(hidden[i]);
      ls[i + 1].rate = (float) rate;
      ls[i + 1].l2 = (float) l2;
    }
    if( classification )
      ls[ls.length - 1] = new VecSoftmax(trainResp, null);
    else
      ls[ls.length - 1] = new VecLinear(trainResp, null);
    ls[ls.length - 1].rate = (float) rate;
    ls[ls.length - 1].l2 = (float) l2;
    for( int i = 0; i < ls.length; i++ )
      ls[i].init(ls, i);

    final Key sourceKey = Key.make(input("source"));
    final Frame frame = new Frame(_names, train);
    frame.add(_responseName, trainResp);
    final NeuralNetModel model = new NeuralNetModel(destination_key, sourceKey, frame, ls);
    UKV.put(destination_key, model);

    final Trainer trainer = new Trainer.MapReduce(ls, epochs, self());

    // Use a separate thread for monitoring (blocked most of the time)
    Thread thread = new Thread() {
      @Override public void run() {
        try {
          Frame[] adapted = null;
          Vec[] valid = null;
          Vec validResp = null;
          if( validation != null ) {
            adapted = model.adapt(validation, false);
            valid = new Vec[adapted[0].vecs().length];
            System.arraycopy(adapted[0].vecs(), 0, valid, 0, valid.length);
            validResp = _validResponse;
          }
          while( !cancelled() ) {
            eval(valid, validResp);
            try {
              Thread.sleep(2000);
            } catch( InterruptedException e ) {
              throw new RuntimeException(e);
            }
          }
          eval(valid, validResp);
          if( adapted != null && adapted[1] != null )
            adapted[1].remove();
        } catch( Exception ex ) {
          cancel(ex);
        }
      }

      private void eval(Vec[] valid, Vec validResp) {
        long[][] cm = null;
        if( classification ) {
          int classes = ls[ls.length - 1].units;
          cm = new long[classes][classes];
        }
        Error trainE = NeuralNet.eval(ls, train, trainResp, EVAL_ROW_COUNT, valid == null ? cm : null);
        Error validE = null;
        if( valid != null )
          validE = NeuralNet.eval(ls, valid, validResp, EVAL_ROW_COUNT, cm);

        model.processed_samples = trainer.samples();
        model.train_classification_error = trainE.classification;
        model.train_mse = trainE.mse;
        model.validation_classification_error = validE != null ? validE.classification : Double.NaN;
        model.validation_mse = validE != null ? validE.mse : Double.NaN;
        model.confusion_matrix = cm;
        UKV.put(model._selfKey, model);
      }
    };
    trainer.start();
    thread.start();
  }

  @Override public float progress() {
    NeuralNetModel model = UKV.get(destination_key);
    if( model != null && source != null && epochs > 0 )
      return 0.1f + Math.min(1, model.processed_samples / (float) (epochs * source.numRows()));
    return 0;
  }

  public static Error eval(Layer[] ls, Frame frame, long n, long[][] cm) {
    Vec[] vecs = frame.vecs();
    vecs = Utils.remove(vecs, vecs.length - 1);
    Vec resp = frame.vecs()[frame.vecs().length - 1];
    return eval(ls, vecs, resp, n, cm);
  }

  public static Error eval(Layer[] ls, Vec[] vecs, Vec resp, long n, long[][] cm) {
    Output output = (Output) ls[ls.length - 1];
    if( output instanceof VecSoftmax )
      output = new VecSoftmax(resp, (VecSoftmax) output);
    else
      output = new VecLinear(resp, (VecLinear) output);
    return eval(ls, new VecsInput(vecs, (VecsInput) ls[0]), output, n, cm);
  }

  public static Error eval(Layer[] ls, Input input, Output output, long n, long[][] cm) {
    Layer[] clones = new Layer[ls.length];
    clones[0] = input;
    for( int y = 1; y < clones.length - 1; y++ )
      clones[y] = ls[y].clone();
    clones[clones.length - 1] = output;
    for( int y = 0; y < clones.length; y++ )
      clones[y].init(clones, y, false, 0);
    Layer.shareWeights(ls, clones);
    return eval(clones, n, cm);
  }

  public static Error eval(Layer[] ls, long n, long[][] cm) {
    Error error = new Error();
    Input input = (Input) ls[0];
    long len = input._len;
    if( n != 0 )
      len = Math.min(len, n);
    if( ls[ls.length - 1] instanceof Softmax ) {
      int correct = 0;
      for( input._pos = 0; input._pos < len; input._pos++ )
        if( correct(ls, error, cm) )
          correct++;
      error.classification = (len - (double) correct) / len;
      error.mse /= len;
    } else {
      for( input._pos = 0; input._pos < len; input._pos++ )
        error(ls, error);
      error.classification = Double.NaN;
      error.mse /= len;
    }
    return error;
  }

  private static boolean correct(Layer[] ls, Error error, long[][] confusion) {
    Softmax output = (Softmax) ls[ls.length - 1];
    for( int i = 0; i < ls.length; i++ )
      ls[i].fprop();
    float[] out = ls[ls.length - 1]._a;
    for( int i = 0; i < out.length; i++ ) {
      float t = i == output.label() ? 1 : 0;
      float d = t - out[i];
      error.mse += d * d;
    }
    float max = Float.NEGATIVE_INFINITY;
    int idx = -1;
    for( int i = 0; i < out.length; i++ ) {
      if( out[i] > max ) {
        max = out[i];
        idx = i;
      }
    }
    if( confusion != null )
      confusion[output.label()][idx]++;
    return idx == output.label();
  }

  // TODO extract to layer
  private static void error(Layer[] ls, Error error) {
    Linear linear = (Linear) ls[ls.length - 1];
    for( int i = 0; i < ls.length; i++ )
      ls[i].fprop();
    float[] out = ls[ls.length - 1]._a;
    for( int i = 0; i < out.length; i++ ) {
      float t = linear.value();
      float d = t - out[i];
      error.mse += d * d;
    }
  }

  @Override protected Response redirect() {
    String n = NeuralNetProgress.class.getSimpleName();
    return new Response(Response.Status.redirect, this, -1, -1, n, //
        "job_key", job_key, //
        "destination_key", destination_key);
  }

  public static String link(Key k, String content) {
    NeuralNet req = new NeuralNet();
    RString rs = new RString("<a href='" + req.href() + ".query?%key_param=%$key'>%content</a>");
    rs.replace("key_param", "source");
    rs.replace("key", k.toString());
    rs.replace("content", content);
    return rs.toString();
  }

  @Override public String speedDescription() {
    return "time/epoch";
  }

  @Override public long speedValue() {
    Value value = DKV.get(dest());
    NeuralNetModel m = value != null ? (NeuralNetModel) value.get() : null;
    double epochsSoFar = m == null ? 0 : m.processed_samples / (double) source.numRows();
    long sv = (epochsSoFar <= 0) ? 0 : (long) (runTimeMs() / epochsSoFar);
    return sv;
  }

  public static class Error {
    double classification;
    double mse;

    @Override public String toString() {
      return String.format("%.2f", (100 * classification)) + "% (MSE:" + String.format("%.2e", mse) + ")";
    }
  }

  public static class NeuralNetModel extends Model {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;

    @API(help = "Layers")
    public Layer[] layers;

    @API(help = "Layer weights")
    public float[][] weights, biases;

    @API(help = "Classification error on the training set (Estimation)")
    public double train_classification_error = 1;

    @API(help = "Square distance error on the training set (Estimation)")
    public double train_mse;

    @API(help = "Classification error on the validation set (Estimation)")
    public double validation_classification_error = 1;

    @API(help = "Square distance error on the validation set (Estimation)")
    public double validation_mse;

    @API(help = "Confusion matrix")
    public long[][] confusion_matrix;

    @API(help = "How many rows have been processed")
    public long processed_samples;

    NeuralNetModel(Key selfKey, Key dataKey, Frame fr, Layer[] ls) {
      super(selfKey, dataKey, fr);

      layers = ls;
      weights = new float[ls.length][];
      biases = new float[ls.length][];
      for( int y = 1; y < layers.length; y++ ) {
        weights[y] = layers[y]._w;
        biases[y] = layers[y]._b;
      }
    }

    @Override protected float[] score0(Chunk[] chunks, int rowInChunk, double[] tmp, float[] preds) {
      Layer[] clones = new Layer[layers.length];
      clones[0] = new ChunksInput(Utils.remove(chunks, chunks.length - 1), (VecsInput) layers[0]);
      for( int y = 1; y < layers.length - 1; y++ )
        clones[y] = layers[y].clone();
      Layer output = layers[layers.length - 1];
      if( output instanceof VecSoftmax )
        clones[clones.length - 1] = new ChunkSoftmax(chunks[chunks.length - 1], (VecSoftmax) output);
      else
        clones[clones.length - 1] = new ChunkLinear(chunks[chunks.length - 1], (VecLinear) output);
      for( int y = 0; y < clones.length; y++ ) {
        clones[y]._w = weights[y];
        clones[y]._b = biases[y];
        clones[y].init(clones, y, false, 0);
      }
      ((Input) clones[0])._pos = rowInChunk;
      for( int i = 0; i < clones.length; i++ )
        clones[i].fprop();
      float[] out = clones[clones.length - 1]._a;
      assert out.length == preds.length;
      return out;
    }

    @Override protected float[] score0(double[] data, float[] preds) {
      throw new UnsupportedOperationException();
    }

    @Override public ConfusionMatrix cm() {
      long[][] cm = confusion_matrix;
      if( cm != null )
        return new ConfusionMatrix(cm);
      return null;
    }

    public Response redirect(Request req) {
      String n = new NeuralNetProgress().href();
      return new Response(Response.Status.redirect, req, -1, -1, n, "destination_key", _selfKey);
    }
  }

  public static class NeuralNetProgress extends Progress2 {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;

    @Override protected String name() {
      return DOC_GET;
    }

    @Override public boolean toHTML(StringBuilder sb) {
      Job nn = job_key == null ? null : Job.findJob(job_key);
      NeuralNetModel model = UKV.get(destination_key);
      if( model != null ) {
        String cmTitle = "Confusion Matrix";
        String trainC = format(model.train_classification_error);
        String trainS = "" + model.train_mse;
        String validC = format(model.validation_classification_error);
        String validS = "" + model.validation_mse;
        if( Double.isNaN(model.validation_mse) ) {
          validS = "N/A";
          cmTitle += " (Training Data)";
        }
        DocGen.HTML.section(sb, "Training classification error: " + trainC);
        DocGen.HTML.section(sb, "Training mean square error: " + trainS);
        DocGen.HTML.section(sb, "Validation classification error: " + validC);
        DocGen.HTML.section(sb, "Validation mean square error: " + validS);
        if( nn != null ) {
          long ps = model.processed_samples * 1000 / nn.runTimeMs();
          DocGen.HTML.section(sb, "Training speed: " + ps + " samples/s");
        }
        if( model.confusion_matrix != null && model.confusion_matrix.length < 100 ) {
          String[] classes = model.classNames();
          NeuralNetScore.confusion(sb, cmTitle, classes, model.confusion_matrix);
        }
      }
      return true;
    }

    private static String format(double classification) {
      String s = "N/A";
      if( !Double.isNaN(classification) )
        s = String.format("%5.2f %%", 100 * classification);
      return s;
    }

    @Override protected Response jobDone(Job job, Key dst) {
      return new Response(Response.Status.done, this, 0, 0, null);
    }

    public static String link(Key job, Key model, String content) {
      NeuralNetProgress req = new NeuralNetProgress();
      return "<a href='" + req.href() + ".html?job_key=" + job + "&destination_key=" + model + "'>" + content + "</a>";
    }
  }

  public static class NeuralNetScore extends ModelJob {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;
    static final String DOC_GET = "Neural network scoring";

    @API(help = "Model", required = true, filter = Default.class)
    public NeuralNetModel model;

    @API(help = "Rows to consider for scoring, 0 (default) means the whole frame", filter = Default.class)
    public long max_rows;

    @API(help = "Classification error")
    public double classification_error;

    @API(help = "Mean square error")
    public double mse;

    @API(help = "Confusion matrix")
    public long[][] confusion_matrix;

    public NeuralNetScore() {
      description = DOC_GET;
    }

    @Override protected Response serve() {
      init();
      Frame[] frs = model.adapt(source, false);
      int classes = model.layers[model.layers.length - 1].units;
      confusion_matrix = new long[classes][classes];
      Layer[] clones = new Layer[model.layers.length];
      for( int y = 0; y < model.layers.length; y++ ) {
        clones[y] = model.layers[y].clone();
        clones[y]._w = model.weights[y];
        clones[y]._b = model.biases[y];
      }
      Error error = eval(clones, frs[0].vecs(), response, max_rows, confusion_matrix);
      classification_error = error.classification;
      mse = error.mse;
      if( frs[1] != null )
        frs[1].remove();
      return new Response(Response.Status.done, this, 0, 0, null);
    }

    @Override public boolean toHTML(StringBuilder sb) {
      DocGen.HTML.section(sb, "Classification error: " + String.format("%5.2f %%", 100 * classification_error));
      DocGen.HTML.section(sb, "Mean square error: " + mse);
      confusion(sb, "Confusion Matrix", response.domain(), confusion_matrix);
      return true;
    }

    static void confusion(StringBuilder sb, String title, String[] classes, long[][] confusionMatrix) {
      sb.append("<h3>" + title + "</h3>");
      sb.append("<table class='table table-striped table-bordered table-condensed'>");
      sb.append("<tr><th>Actual \\ Predicted</th>");
      if( classes == null ) {
        classes = new String[confusionMatrix.length];
        for( int i = 0; i < classes.length; i++ )
          classes[i] = "" + i;
      }
      for( String c : classes )
        sb.append("<th>" + c + "</th>");
      sb.append("<th>Error</th></tr>");
      long[] totals = new long[classes.length];
      long sumTotal = 0;
      long sumError = 0;
      for( int crow = 0; crow < classes.length; ++crow ) {
        long total = 0;
        long error = 0;
        sb.append("<tr><th>" + classes[crow] + "</th>");
        for( int ccol = 0; ccol < classes.length; ++ccol ) {
          long num = confusionMatrix[crow][ccol];
          total += num;
          totals[ccol] += num;
          if( ccol == crow ) {
            sb.append("<td style='background-color:LightGreen'>");
          } else {
            sb.append("<td>");
            error += num;
          }
          sb.append(num);
          sb.append("</td>");
        }
        sb.append("<td>");
        sb.append(String.format("%5.3f = %d / %d", (double) error / total, error, total));
        sb.append("</td></tr>");
        sumTotal += total;
        sumError += error;
      }
      sb.append("<tr><th>Totals</th>");
      for( int i = 0; i < totals.length; ++i )
        sb.append("<td>" + totals[i] + "</td>");
      sb.append("<td><b>");
      sb.append(String.format("%5.3f = %d / %d", (double) sumError / sumTotal, sumError, sumTotal));
      sb.append("</b></td></tr>");
      sb.append("</table>");
    }
  }

  static int cores() {
    int cores = 0;
    for( H2ONode node : H2O.CLOUD._memary )
      cores += node._heartbeat._num_cpus;
    return cores;
  }

  /**
   * Makes sure small datasets are spread over enough chunks to parallelize training.
   */
  public static void reChunk(Vec[] vecs) {
    final int splits = cores() * 2; // More in case of unbalance
    if( vecs[0].nChunks() < splits ) {
      // A new random VectorGroup
      Key keys[] = new Vec.VectorGroup().addVecs(vecs.length);
      for( int v = 0; v < vecs.length; v++ ) {
        AppendableVec vec = new AppendableVec(keys[v]);
        long rows = vecs[0].length();
        Chunk cache = null;
        for( int split = 0; split < splits; split++ ) {
          long off = rows * (split + 0) / splits;
          long lim = rows * (split + 1) / splits;
          NewChunk chunk = new NewChunk(vec, split);
          for( long r = off; r < lim; r++ ) {
            if( cache == null || r < cache._start || r >= cache._start + cache._len )
              cache = vecs[v].chunk(r);
            if( !cache.isNA(r) ) {
              if( vecs[v]._domain != null )
                chunk.addEnum((int) cache.at8(r));
              else if( vecs[v].isInt() )
                chunk.addNum(cache.at8(r), 0);
              else
                chunk.addNum(cache.at(r));
            } else {
              if( vecs[v].isInt() )
                chunk.addNA();
              else {
                // Don't use addNA() for doubles, as NewChunk uses separate array
                chunk.addNum(Double.NaN);
              }
            }
          }
          chunk.close(split, null);
        }
        Vec t = vec.close(null);
        t._domain = vecs[v]._domain;
        vecs[v] = t;
      }
    }
  }
}
