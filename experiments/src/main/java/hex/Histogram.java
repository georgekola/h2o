package hex;

import hex.Histogram;
import hex.Layer;

import java.util.ArrayList;
import java.util.concurrent.*;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.JFXPanel;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import javax.swing.SwingUtilities;

public class Histogram extends LineChart {
  private static final int SLICES = 64;

  private static final ArrayList<Histogram> _instances = new ArrayList<Histogram>();
  private static final ScheduledExecutorService _executor = Executors.newSingleThreadScheduledExecutor();
  private static CheckBox _auto;

  private final float[] _data;
  private final ObservableList<Data<Float, Float>> _list = FXCollections.observableArrayList();

  static void init() {
    final CountDownLatch latch = new CountDownLatch(1);
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        initFromSwingThread();
        latch.countDown();
      }
    });
    try {
      latch.await();
    } catch( InterruptedException e ) {
      throw new RuntimeException(e);
    }
  }

  static void initFromSwingThread() {
    new JFXPanel(); // initializes JavaFX environment
  }

  public static void build(final Layer[] ls) {
    Platform.runLater(new Runnable() {
      @Override public void run() {
        VBox v = new VBox();
        for( int i = ls.length - 1; i > 0; i-- ) {
          HBox h = new HBox();
          h.getChildren().add(new Histogram("Layer " + i + " A", ls[i]._a));
          h.getChildren().add(new Histogram("E", ls[i]._e));
          v.getChildren().add(h);

          h = new HBox();
          h.getChildren().add(new Histogram("Layer " + i + " W", ls[i]._w));
          h.getChildren().add(new Histogram("B", ls[i]._b));
          v.getChildren().add(h);

          h = new HBox();
          h.getChildren().add(new Histogram("Layer " + i + " W S", ls[i]._wSpeed));
          h.getChildren().add(new Histogram("W B", ls[i]._bSpeed));
          v.getChildren().add(h);

          if( ls[i]._v != null ) {
            h = new HBox();
            h.getChildren().add(new Histogram("Layer " + i + " V", ls[i]._v));
            h.getChildren().add(new Histogram("Gradient " + i + " V", ls[i]._gv));
            v.getChildren().add(h);
          }
        }
        Stage stage = new Stage();
        BorderPane root = new BorderPane();
        ToolBar toolbar = new ToolBar();

        Button refresh = new Button("Refresh");
        refresh.setOnAction(new EventHandler<ActionEvent>() {
          @Override public void handle(ActionEvent e) {
            refresh();
          }
        });
        toolbar.getItems().add(refresh);

        _auto = new CheckBox("Auto");
        _auto.selectedProperty().addListener(new ChangeListener<Boolean>() {
          public void changed(ObservableValue<? extends Boolean> ov, Boolean old_val, Boolean new_val) {
            refresh();
          }
        });
        toolbar.getItems().add(_auto);

        root.setTop(toolbar);
        ScrollPane scroll = new ScrollPane();
        scroll.setContent(v);
        root.setCenter(scroll);
        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setWidth(1500);
        stage.setHeight(1100);
        stage.show();

        scene.getWindow().onCloseRequestProperty().addListener(new ChangeListener() {
          @Override public void changed(ObservableValue arg0, Object arg1, Object arg2) {
            _auto.selectedProperty().set(false);
          }
        });
        refresh();
      }
    });
  }

  public Histogram(String title, float[] data) {
    super(new NumberAxis(), new NumberAxis());
    _data = data;

    ObservableList<Series<Float, Float>> series = FXCollections.observableArrayList();
    for( int i = 0; i < SLICES; i++ )
      _list.add(new Data<Float, Float>(0f, 0f));
    series.add(new LineChart.Series<Float, Float>(title, _list));
    setData(series);
    setPrefWidth(600);
    setPrefHeight(250);

    _instances.add(this);
  }

  static void refresh() {
    for( Histogram h : _instances ) {
      if( h._data != null ) {
        float[] data = h._data.clone();
        float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
        for( int i = 0; i < data.length; i++ ) {
          max = Math.max(max, data[i]);
          min = Math.min(min, data[i]);
        }
        int[] counts = new int[SLICES];
        float inc = (max - min) / (SLICES - 1);
        for( int i = 0; i < data.length; i++ )
          counts[(int) Math.floor((data[i] - min) / inc)]++;

        for( int i = 0; i < SLICES; i++ ) {
          Data<Float, Float> point = h._list.get(i);
          point.setXValue(min + inc * i);
          point.setYValue((float) counts[i] / data.length);
        }
      }
    }

    if( _auto.selectedProperty().get() ) {
      _executor.schedule(new Runnable() {

        @Override public void run() {
          Platform.runLater(new Runnable() {
            @Override public void run() {
              refresh();
            }
          });
        }
      }, 1000, TimeUnit.MILLISECONDS);
    }
  }
}