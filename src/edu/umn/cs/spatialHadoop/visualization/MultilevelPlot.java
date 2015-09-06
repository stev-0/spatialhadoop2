/***********************************************************************
* Copyright (c) 2015 by Regents of the University of Minnesota.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which 
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*
*************************************************************************/
package edu.umn.cs.spatialHadoop.visualization;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Vector;

import javax.imageio.ImageIO;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.LocalJobRunner;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.util.LineReader;

import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.GridInfo;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.Shape;
import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.mapreduce.RTreeRecordReader3;
import edu.umn.cs.spatialHadoop.mapreduce.SpatialInputFormat3;
import edu.umn.cs.spatialHadoop.mapreduce.SpatialRecordReader3;
import edu.umn.cs.spatialHadoop.nasa.HDFRecordReader;
import edu.umn.cs.spatialHadoop.operations.FileMBR;
import edu.umn.cs.spatialHadoop.util.Parallel;
import edu.umn.cs.spatialHadoop.util.Parallel.RunnableRange;

/**
 * Generates a multilevel image
 * @author Ahmed Eldawy
 *
 */
public class MultilevelPlot {
  private static final Log LOG = LogFactory.getLog(MultilevelPlot.class);
  /**Configuration entry for input MBR*/
  private static final String InputMBR = "mbr";

  /**Maximum height for a pyramid to be generated by one machine*/
  private static final String MaxLevelsPerReducer = "MultilevelPlot.MaxLevelsPerMachine";

  /**The maximum level on which flat partitioning can be used*/
  private static final String FlatPartitioningLevelThreshold = "MultilevelPlot.FlatPartitioningLevelThreshold";
  
  public static class FlatPartitionMap extends
      Mapper<Rectangle, Iterable<? extends Shape>, TileIndex, RasterLayer> {
    /**Minimum and maximum levels of the pyramid to plot (inclusive and zero-based)*/
    private int minLevel, maxLevel;
    
    /**The grid at the bottom level (i.e., maxLevel)*/
    private GridInfo bottomGrid;

    /**The MBR of the input area to draw*/
    private Rectangle inputMBR;

    /**The rasterizer associated with this job*/
    private Rasterizer rasterizer;

    /**Fixed width for one tile*/
    private int tileWidth;

    /**Fixed height for one tile */
    private int tileHeight;
    
    /**Buffer size that should be taken in the maximum level*/
    private double bufferSizeXMaxLevel;

    private double bufferSizeYMaxLevel;

    /**Whether the configured rasterize supports smooth or not*/
    private boolean smooth;

    @Override
    protected void setup(Context context)
        throws IOException, InterruptedException {
      super.setup(context);
      Configuration conf = context.getConfiguration();
      String[] strLevels = conf.get("levels", "7").split("\\.\\.");
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }
      this.inputMBR = (Rectangle) OperationsParams.getShape(conf, InputMBR);
      this.bottomGrid = new GridInfo(inputMBR.x1, inputMBR.y1, inputMBR.x2, inputMBR.y2);
      this.bottomGrid.rows = bottomGrid.columns = 1 << maxLevel;
      this.tileWidth = conf.getInt("tilewidth", 256);
      this.tileHeight = conf.getInt("tileheight", 256);
      this.rasterizer = Rasterizer.getRasterizer(conf);
      this.smooth = rasterizer.isSmooth();
      int radius = rasterizer.getRadius();
      this.bufferSizeXMaxLevel = radius * inputMBR.getWidth() / (tileWidth * (1 << maxLevel));
      this.bufferSizeYMaxLevel = radius * inputMBR.getHeight() / (tileHeight * (1 << maxLevel));
    }
    
    @Override
    protected void map(Rectangle partition, Iterable<? extends Shape> shapes,
        Context context) throws IOException, InterruptedException {
      if (smooth)
        shapes = rasterizer.smooth(shapes);
      TileIndex key = new TileIndex();
      Map<TileIndex, RasterLayer> rasterLayers = new HashMap<TileIndex, RasterLayer>();
      int i = 0; // Counter to report progress often
      for (Shape shape : shapes) {
        Rectangle shapeMBR = shape.getMBR();
        if (shapeMBR == null)
          continue;
        java.awt.Rectangle overlappingCells =
            bottomGrid.getOverlappingCells(shapeMBR.buffer(bufferSizeXMaxLevel, bufferSizeYMaxLevel));
        // Iterate over levels from bottom up
        for (key.level = maxLevel; key.level >= minLevel; key.level--) {
          for (key.x = overlappingCells.x; key.x < overlappingCells.x + overlappingCells.width; key.x++) {
            for (key.y = overlappingCells.y; key.y < overlappingCells.y + overlappingCells.height; key.y++) {
              RasterLayer rasterLayer = rasterLayers.get(key);
              if (rasterLayer == null) {
                Rectangle tileMBR = new Rectangle();
                int gridSize = 1 << key.level;
                tileMBR.x1 = (inputMBR.x1 * (gridSize - key.x) + inputMBR.x2 * key.x) / gridSize;
                tileMBR.x2 = (inputMBR.x1 * (gridSize - (key.x + 1)) + inputMBR.x2 * (key.x+1)) / gridSize;
                tileMBR.y1 = (inputMBR.y1 * (gridSize - key.y) + inputMBR.y2 * key.y) / gridSize;
                tileMBR.y2 = (inputMBR.y1 * (gridSize - (key.y + 1)) + inputMBR.y2 * (key.y+1)) / gridSize;
                rasterLayer = rasterizer.createRaster(tileWidth, tileHeight, tileMBR);
                rasterLayers.put(key.clone(), rasterLayer);
              }
              rasterizer.rasterize(rasterLayer, shape);
            }
          }
          // Update overlappingCells for the higher level
          int updatedX1 = overlappingCells.x / 2;
          int updatedY1 = overlappingCells.y / 2;
          int updatedX2 = (overlappingCells.x + overlappingCells.width - 1) / 2;
          int updatedY2 = (overlappingCells.y + overlappingCells.height - 1) / 2;
          overlappingCells.x = updatedX1;
          overlappingCells.y = updatedY1;
          overlappingCells.width = updatedX2 - updatedX1 + 1;
          overlappingCells.height = updatedY2 - updatedY1 + 1;
        }
        if (((++i) & 0xff) == 0)
          context.progress();
      }
      // Write all created layers to the output
      for (Map.Entry<TileIndex, RasterLayer> entry : rasterLayers.entrySet()) {
        context.write(entry.getKey(), entry.getValue());
      }
    }
  }
  
  public static class FlatPartitionReduce
      extends Reducer<TileIndex, RasterLayer, TileIndex, RasterLayer> {
    /**Minimum and maximum levels of the pyramid to plot (inclusive and zero-based)*/
    private int minLevel, maxLevel;
    
    /**The grid at the bottom level (i.e., maxLevel)*/
    private GridInfo bottomGrid;

    /**The MBR of the input area to draw*/
    private Rectangle inputMBR;

    /**The rasterizer associated with this job*/
    private Rasterizer rasterizer;

    /**Fixed width for one tile*/
    private int tileWidth;

    /**Fixed height for one tile */
    private int tileHeight;
    
    @Override
    protected void setup(Context context)
        throws IOException, InterruptedException {
      super.setup(context);
      Configuration conf = context.getConfiguration();
      String[] strLevels = conf.get("levels", "7").split("\\.\\.");
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }
      this.inputMBR = (Rectangle) OperationsParams.getShape(conf, InputMBR);
      this.bottomGrid = new GridInfo(inputMBR.x1, inputMBR.y1, inputMBR.x2, inputMBR.y2);
      this.bottomGrid.rows = bottomGrid.columns = 1 << maxLevel;
      this.tileWidth = conf.getInt("tilewidth", 256);
      this.tileHeight = conf.getInt("tileheight", 256);
      this.rasterizer = Rasterizer.getRasterizer(conf);
    }
    
    @Override
    protected void reduce(TileIndex tileID, Iterable<RasterLayer> interLayers,
        Context context) throws IOException, InterruptedException {
      Rectangle tileMBR = new Rectangle();
      int gridSize = 1 << tileID.level;
      tileMBR.x1 = (inputMBR.x1 * (gridSize - tileID.x) + inputMBR.x2 * tileID.x) / gridSize;
      tileMBR.x2 = (inputMBR.x1 * (gridSize - (tileID.x + 1)) + inputMBR.x2 * (tileID.x+1)) / gridSize;
      tileMBR.y1 = (inputMBR.y1 * (gridSize - tileID.y) + inputMBR.y2 * tileID.y) / gridSize;
      tileMBR.y2 = (inputMBR.y1 * (gridSize - (tileID.y + 1)) + inputMBR.y2 * (tileID.y+1)) / gridSize;

      RasterLayer finalLayer = rasterizer.createRaster(tileWidth, tileHeight, tileMBR);
      for (RasterLayer interLayer : interLayers) {
        rasterizer.merge(finalLayer, interLayer);
        context.progress();
      }
      
      context.write(tileID, finalLayer);
    }
  }
  
  public static class PyramidPartitionMap extends
      Mapper<Rectangle, Iterable<? extends Shape>, TileIndex, Shape> {

    private int minLevel, maxLevel;
    /**Maximum level to replicate to*/
    private int maxLevelToReplicate;
    private Rectangle inputMBR;
    /**The grid of the lowest (deepest) level of the pyramid*/
    private GridInfo bottomGrid;
    /**The user-configured rasterizer*/
    private Rasterizer rasterizer;
    /**The radius of effect of each record in input coordinates*/
    private double bufferSizeXMaxLevel, bufferSizeYMaxLevel;
    /**Maximum levels to generate per reducer*/
    private int maxLevelsPerReducer;
    /**Radius of effect of each shape*/
    private int radius;
    
    @Override
    protected void setup(Context context)
        throws IOException, InterruptedException {
      super.setup(context);
      Configuration conf = context.getConfiguration();
      String[] strLevels = conf.get("levels", "7").split("\\.\\.");
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }
      this.maxLevelsPerReducer = conf.getInt(MaxLevelsPerReducer, 3);
      // Adjust maxLevelToReplicate so that the difference is multiple of maxLevelsPerMachine
      this.maxLevelToReplicate = maxLevel - (maxLevel - minLevel) % maxLevelsPerReducer;
      this.inputMBR = (Rectangle) OperationsParams.getShape(conf, InputMBR);
      this.bottomGrid = new GridInfo(inputMBR.x1, inputMBR.y1, inputMBR.x2, inputMBR.y2);
      this.bottomGrid.rows = bottomGrid.columns = (1 << maxLevelToReplicate); // 2 ^ maxLevel
      int tileWidth = conf.getInt("tilewidth", 256);
      int tileHeight = conf.getInt("tileheight", 256);
      this.rasterizer = Rasterizer.getRasterizer(conf);
      this.radius = rasterizer.getRadius();
      this.bufferSizeXMaxLevel = radius * inputMBR.getWidth() / (tileWidth * (1 << maxLevelToReplicate));
      this.bufferSizeYMaxLevel = radius * inputMBR.getHeight() / (tileHeight * (1 << maxLevelToReplicate));
    }
    
    @Override
    protected void map(Rectangle partition, Iterable<? extends Shape> shapes,
        Context context) throws IOException, InterruptedException {
      TileIndex outKey = new TileIndex();
      int i = 0;
      for (Shape shape : shapes) {
        Rectangle shapeMBR = shape.getMBR();
        if (shapeMBR == null)
          continue;
        java.awt.Rectangle overlappingCells =
            bottomGrid.getOverlappingCells(shapeMBR.buffer(bufferSizeXMaxLevel, bufferSizeYMaxLevel));
        // Iterate over levels from bottom up
        for (outKey.level = maxLevelToReplicate; outKey.level >= minLevel; outKey.level -= maxLevelsPerReducer) {
          for (outKey.x = overlappingCells.x; outKey.x < overlappingCells.x + overlappingCells.width; outKey.x++) {
            for (outKey.y = overlappingCells.y; outKey.y < overlappingCells.y + overlappingCells.height; outKey.y++) {
              context.write(outKey, shape);
            }
          }
          // Shrink overlapping cells to match the upper level
          int updatedX1 = overlappingCells.x >> maxLevelsPerReducer;
          int updatedY1 = overlappingCells.y >> maxLevelsPerReducer;
          int updatedX2 = (overlappingCells.x + overlappingCells.width - 1) >> maxLevelsPerReducer;
          int updatedY2 = (overlappingCells.y + overlappingCells.height - 1) >> maxLevelsPerReducer;
          overlappingCells.x = updatedX1;
          overlappingCells.y = updatedY1;
          overlappingCells.width = updatedX2 - updatedX1 + 1;
          overlappingCells.height = updatedY2 - updatedY1 + 1;
        }
        if (((++i) & 0xff) == 0)
          context.progress();
      }
    }
  }
  
  public static class PyramidPartitionReduce extends
      Reducer<TileIndex, Shape, TileIndex, RasterLayer> {

    private int minLevel, maxLevel;
    /**Maximum level to replicate to*/
    private int maxLevelToReplicate;
    private Rectangle inputMBR;
    /**The grid of the lowest (deepest) level of the pyramid*/
    private GridInfo bottomGrid;
    /**The user-configured rasterizer*/
    private Rasterizer rasterizer;
    /**The radius of effect of each record in input coordinates*/
    private double bufferSizeXMaxLevel, bufferSizeYMaxLevel;
    /**Maximum levels to generate per reducer*/
    private int maxLevelsPerReducer;
    /**Size of each tile in pixels*/
    private int tileWidth, tileHeight;
    /**Radius of effect of each shape*/
    private int radius;
    /**Whether the configured rasterizer defines a smooth function or not*/
    private boolean smooth;
    
    @Override
    protected void setup(Context context)
        throws IOException, InterruptedException {
      super.setup(context);
      Configuration conf = context.getConfiguration();
      String[] strLevels = conf.get("levels", "7").split("\\.\\.");
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }
      this.maxLevelsPerReducer = conf.getInt(MaxLevelsPerReducer, 3);
      // Adjust maxLevelToReplicate so that the difference is multiple of maxLevelsPerMachine
      this.maxLevelToReplicate = maxLevel - (maxLevel - minLevel) % maxLevelsPerReducer;
      this.inputMBR = (Rectangle) OperationsParams.getShape(conf, InputMBR);
      this.bottomGrid = new GridInfo(inputMBR.x1, inputMBR.y1, inputMBR.x2, inputMBR.y2);
      this.bottomGrid.rows = bottomGrid.columns = (1 << maxLevelToReplicate); // 2 ^ maxLevel
      int tileWidth = conf.getInt("tilewidth", 256);
      int tileHeight = conf.getInt("tileheight", 256);
      this.rasterizer = Rasterizer.getRasterizer(conf);
      this.smooth = rasterizer.isSmooth();
      this.radius = rasterizer.getRadius();
      this.bufferSizeXMaxLevel = radius * inputMBR.getWidth() / (tileWidth * (1 << maxLevelToReplicate));
      this.bufferSizeYMaxLevel = radius * inputMBR.getHeight() / (tileHeight * (1 << maxLevelToReplicate));
      this.tileWidth = conf.getInt("tilewidth", 256);
      this.tileHeight = conf.getInt("tileheight", 256);
    }
    
    @Override
    protected void reduce(TileIndex tileID, Iterable<Shape> shapes, Context context)
        throws IOException, InterruptedException {
      // Find first and last levels to generate in this reducer
      int level1 = Math.max(tileID.level, minLevel);
      int level2 = Math.min(tileID.level + maxLevelsPerReducer - 1, maxLevel);

      // Portion of the bottom grid that falls under the given tile
      int tileOffsetX = tileID.x << (level2 - tileID.level);
      int tileOffsetY = tileID.y << (level2 - tileID.level);
      GridInfo bottomGrid = new GridInfo();
      int gridSize = 1 << tileID.level;
      bottomGrid.x1 = (inputMBR.x1 * (gridSize - tileID.x) + inputMBR.x2 * tileID.x) / gridSize;
      bottomGrid.x2 = (inputMBR.x1 * (gridSize - (tileID.x + 1)) + inputMBR.x2 * (tileID.x+1)) / gridSize;
      bottomGrid.y1 = (inputMBR.y1 * (gridSize - tileID.y) + inputMBR.y2 * tileID.y) / gridSize;
      bottomGrid.y2 = (inputMBR.y1 * (gridSize - (tileID.y + 1)) + inputMBR.y2 * (tileID.y+1)) / gridSize;
      bottomGrid.columns = bottomGrid.rows = (1 << (level2 - level1));
      double bufferSizeXLevel2 = radius * inputMBR.getWidth() / (tileWidth * (1 << level2));
      double bufferSizeYLevel2 = radius * inputMBR.getHeight() / (tileHeight * (1 << level2));
      Map<TileIndex, RasterLayer> rasterLayers = new HashMap<TileIndex, RasterLayer>();
      
      TileIndex key = new TileIndex();
      
      context.setStatus("Rasterizing");
      if (smooth) {
        shapes = rasterizer.smooth(shapes);
        context.progress();
      }
      int i = 0;
      for (Shape shape : shapes) {
        Rectangle shapeMBR = shape.getMBR();
        if (shapeMBR == null)
          continue;
        java.awt.Rectangle overlappingCells =
            bottomGrid.getOverlappingCells(shapeMBR.buffer(bufferSizeXLevel2, bufferSizeYLevel2));
        // Shift overlapping cells to be in the full pyramid rather than
        // the sub-pyramid rooted at tileID
        overlappingCells.x += tileOffsetX;
        overlappingCells.y += tileOffsetY;
        // Iterate over levels from bottom up
        for (key.level = level2; key.level >= level1; key.level--) {
          for (key.x = overlappingCells.x; key.x < overlappingCells.x + overlappingCells.width; key.x++) {
            for (key.y = overlappingCells.y; key.y < overlappingCells.y + overlappingCells.height; key.y++) {
              RasterLayer rasterLayer = rasterLayers.get(key);
              if (rasterLayer == null) {
                Rectangle tileMBR = new Rectangle();
                gridSize = 1 << key.level;
                tileMBR.x1 = (inputMBR.x1 * (gridSize - key.x) + inputMBR.x2 * key.x) / gridSize;
                tileMBR.x2 = (inputMBR.x1 * (gridSize - (key.x + 1)) + inputMBR.x2 * (key.x+1)) / gridSize;
                tileMBR.y1 = (inputMBR.y1 * (gridSize - key.y) + inputMBR.y2 * key.y) / gridSize;
                tileMBR.y2 = (inputMBR.y1 * (gridSize - (key.y + 1)) + inputMBR.y2 * (key.y+1)) / gridSize;
                rasterLayer = rasterizer.createRaster(tileWidth, tileHeight, tileMBR);
                rasterLayers.put(key.clone(), rasterLayer);
              }
              rasterizer.rasterize(rasterLayer, shape);
            }
          }
          
          // Update overlappingCells for the higher level
          int updatedX1 = overlappingCells.x / 2;
          int updatedY1 = overlappingCells.y / 2;
          int updatedX2 = (overlappingCells.x + overlappingCells.width - 1) / 2;
          int updatedY2 = (overlappingCells.y + overlappingCells.height - 1) / 2;
          overlappingCells.x = updatedX1;
          overlappingCells.y = updatedY1;
          overlappingCells.width = updatedX2 - updatedX1 + 1;
          overlappingCells.height = updatedY2 - updatedY1 + 1;
        }
        
        if (((++i) & 0xff) == 0)
          context.progress();
      }
      context.setStatus("Writing "+rasterLayers.size()+" tiles");
      // Write all created layers to the output as images
      for (Map.Entry<TileIndex, RasterLayer> entry : rasterLayers.entrySet()) {
        context.write(entry.getKey(), entry.getValue());
      }
    }
  }

  private static Job plotMapReduce(Path[] inFiles, Path outFile,
      Class<? extends Rasterizer> rasterizerClass, OperationsParams params)
      throws IOException, InterruptedException, ClassNotFoundException {
    Rasterizer rasterizer;
    try {
      rasterizer = rasterizerClass.newInstance();
    } catch (InstantiationException e) {
      throw new RuntimeException("Error creating rastierizer", e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Error creating rastierizer", e);
    }
    
    Job job = new Job(params, "MultilevelPlot");
    job.setJarByClass(SingleLevelPlot.class);
    // Set rasterizer
    Configuration conf = job.getConfiguration();
    Rasterizer.setRasterizer(conf, rasterizerClass);
    // Set input file MBR
    Rectangle inputMBR = (Rectangle) params.getShape("mbr");
    if (inputMBR == null)
      inputMBR = FileMBR.fileMBR(inFiles, params);
    
    // Adjust width and height if aspect ratio is to be kept
    if (params.getBoolean("keepratio", true)) {
      // Expand input file to a rectangle for compatibility with the pyramid
      // structure
      if (inputMBR.getWidth() > inputMBR.getHeight()) {
        inputMBR.y1 -= (inputMBR.getWidth() - inputMBR.getHeight()) / 2;
        inputMBR.y2 = inputMBR.y1 + inputMBR.getWidth();
      } else {
        inputMBR.x1 -= (inputMBR.getHeight() - inputMBR.getWidth() / 2);
        inputMBR.x2 = inputMBR.x1 + inputMBR.getHeight();
      }
    }
    OperationsParams.setShape(conf, InputMBR, inputMBR);
    
    // Set input and output
    job.setInputFormatClass(SpatialInputFormat3.class);
    SpatialInputFormat3.setInputPaths(job, inFiles);
    if (conf.getBoolean("output", true)) {
      job.setOutputFormatClass(PyramidOutputFormat2.class);
      PyramidOutputFormat2.setOutputPath(job, outFile);
    } else {
      job.setOutputFormatClass(NullOutputFormat.class);
    }
    
    // Set mapper, reducer and committer
    String partitionTechnique = params.get("partition", "flat");
    if (partitionTechnique.equalsIgnoreCase("flat")) {
      // Use flat partitioning
      job.setMapperClass(FlatPartitionMap.class);
      job.setMapOutputKeyClass(TileIndex.class);
      job.setMapOutputValueClass(rasterizer.getRasterClass());
      job.setReducerClass(FlatPartitionReduce.class);
    } else if (partitionTechnique.equalsIgnoreCase("pyramid")) {
      // Use pyramid partitioning
      Shape shape = params.getShape("shape");
      job.setMapperClass(PyramidPartitionMap.class);
      job.setMapOutputKeyClass(TileIndex.class);
      job.setMapOutputValueClass(shape.getClass());
      job.setReducerClass(PyramidPartitionReduce.class);
    } else {
      throw new RuntimeException("Unknown partitioning technique '"+partitionTechnique+"'");
    }
    // Set number of reducers
    job.setNumReduceTasks(Math.max(1, new JobClient(new JobConf())
        .getClusterStatus().getMaxReduceTasks() * 7 / 8));
    // Use multithreading in case the job is running locally
    conf.setInt(LocalJobRunner.LOCAL_MAX_MAPS, Runtime.getRuntime().availableProcessors());

    // Start the job
    if (params.getBoolean("background", false)) {
      job.submit();
    } else {
      job.waitForCompletion(false);
    }
    return job;
  }
  
  private static void plotLocal(Path[] inFiles, final Path outPath,
      final Class<? extends Rasterizer> rasterizerClass, final OperationsParams params)
      throws IOException, InterruptedException, ClassNotFoundException {
    final boolean vflip = params.getBoolean("vflip", true);
    
    OperationsParams mbrParams = new OperationsParams(params);
    mbrParams.setBoolean("background", false);
    final Rectangle inputMBR = params.get("mbr") != null ?
        params.getShape("mbr").getMBR() : FileMBR.fileMBR(inFiles, mbrParams);
    OperationsParams.setShape(params, InputMBR, inputMBR);

    // Retrieve desired output image size and keep aspect ratio if needed
    int tileWidth = params.getInt("tilewidth", 256);
    int tileHeight = params.getInt("tileheight", 256);
    // Adjust width and height if aspect ratio is to be kept
    if (params.getBoolean("keepratio", true)) {
      // Expand input file to a rectangle for compatibility with the pyramid
      // structure
      if (inputMBR.getWidth() > inputMBR.getHeight()) {
        inputMBR.y1 -= (inputMBR.getWidth() - inputMBR.getHeight()) / 2;
        inputMBR.y2 = inputMBR.y1 + inputMBR.getWidth();
      } else {
        inputMBR.x1 -= (inputMBR.getHeight() - inputMBR.getWidth() / 2);
        inputMBR.x2 = inputMBR.x1 + inputMBR.getHeight();
      }
    }

    // Start reading input file
    Vector<InputSplit> splits = new Vector<InputSplit>();
    final SpatialInputFormat3<Rectangle, Shape> inputFormat =
        new SpatialInputFormat3<Rectangle, Shape>();
    for (Path inFile : inFiles) {
      FileSystem inFs = inFile.getFileSystem(params);
      if (!OperationsParams.isWildcard(inFile) && inFs.exists(inFile) && !inFs.isDirectory(inFile)) {
        if (SpatialSite.NonHiddenFileFilter.accept(inFile)) {
          // Use the normal input format splitter to add this non-hidden file
          Job job = Job.getInstance(params);
          SpatialInputFormat3.addInputPath(job, inFile);
          splits.addAll(inputFormat.getSplits(job));
        } else {
          // A hidden file, add it immediately as one split
          // This is useful if the input is a hidden file which is automatically
          // skipped by FileInputFormat. We need to plot a hidden file for the case
          // of plotting partition boundaries of a spatial index
          splits.add(new FileSplit(inFile, 0,
              inFs.getFileStatus(inFile).getLen(), new String[0]));
        }
      } else {
        Job job = Job.getInstance(params);
        SpatialInputFormat3.addInputPath(job, inFile);
        splits.addAll(inputFormat.getSplits(job));
      }
    }

    try {
      Rasterizer rasterizer = rasterizerClass.newInstance();
      rasterizer.configure(params);
      
      String[] strLevels = params.get("levels", "7").split("\\.\\.");
      int minLevel, maxLevel;
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }
      
      int radius = rasterizer.getRadius();
      double bufferSizeXMaxLevel = radius * inputMBR.getWidth() / (tileWidth * (1 << maxLevel));
      double bufferSizeYMaxLevel = radius * inputMBR.getHeight() / (tileHeight * (1 << maxLevel));
      
      GridInfo bottomGrid = new GridInfo(inputMBR.x1, inputMBR.y1, inputMBR.x2, inputMBR.y2);
      bottomGrid.rows = bottomGrid.columns = 1 << maxLevel;
      
      TileIndex key = new TileIndex();
      
      // All raster layers in the pyramid, one per tile
      Map<TileIndex, RasterLayer> rasterLayers = new HashMap<TileIndex, RasterLayer>();
      for (InputSplit split : splits) {
        FileSplit fsplit = (FileSplit) split;
        RecordReader<Rectangle, Iterable<Shape>> reader =
            inputFormat.createRecordReader(fsplit, null);
        if (reader instanceof SpatialRecordReader3) {
          ((SpatialRecordReader3)reader).initialize(fsplit, params);
        } else if (reader instanceof RTreeRecordReader3) {
          ((RTreeRecordReader3)reader).initialize(fsplit, params);
        } else if (reader instanceof HDFRecordReader) {
          ((HDFRecordReader)reader).initialize(fsplit, params);
        } else {
          throw new RuntimeException("Unknown record reader");
        }

        while (reader.nextKeyValue()) {
          Rectangle partition = reader.getCurrentKey();
          if (!partition.isValid())
            partition.set(inputMBR);

          Iterable<Shape> shapes = reader.getCurrentValue();
          
          for (Shape shape : shapes) {
            Rectangle shapeMBR = shape.getMBR();
            if (shapeMBR == null)
              continue;
            java.awt.Rectangle overlappingCells =
                bottomGrid.getOverlappingCells(shapeMBR.buffer(bufferSizeXMaxLevel, bufferSizeYMaxLevel));
            // Iterate over levels from bottom up
            for (key.level = maxLevel; key.level >= minLevel; key.level--) {
              for (key.x = overlappingCells.x; key.x < overlappingCells.x + overlappingCells.width; key.x++) {
                for (key.y = overlappingCells.y; key.y < overlappingCells.y + overlappingCells.height; key.y++) {
                  RasterLayer rasterLayer = rasterLayers.get(key);
                  if (rasterLayer == null) {
                    Rectangle tileMBR = new Rectangle();
                    int gridSize = 1 << key.level;
                    tileMBR.x1 = (inputMBR.x1 * (gridSize - key.x) + inputMBR.x2 * key.x) / gridSize;
                    tileMBR.x2 = (inputMBR.x1 * (gridSize - (key.x + 1)) + inputMBR.x2 * (key.x+1)) / gridSize;
                    tileMBR.y1 = (inputMBR.y1 * (gridSize - key.y) + inputMBR.y2 * key.y) / gridSize;
                    tileMBR.y2 = (inputMBR.y1 * (gridSize - (key.y + 1)) + inputMBR.y2 * (key.y+1)) / gridSize;
                    rasterLayer = rasterizer.createRaster(tileWidth, tileHeight, tileMBR);
                    rasterLayers.put(key.clone(), rasterLayer);
                  }
                  rasterizer.rasterize(rasterLayer, shape);
                }
              }
              // Update overlappingCells for the higher level
              int updatedX1 = overlappingCells.x / 2;
              int updatedY1 = overlappingCells.y / 2;
              int updatedX2 = (overlappingCells.x + overlappingCells.width - 1) / 2;
              int updatedY2 = (overlappingCells.y + overlappingCells.height - 1) / 2;
              overlappingCells.x = updatedX1;
              overlappingCells.y = updatedY1;
              overlappingCells.width = updatedX2 - updatedX1 + 1;
              overlappingCells.height = updatedY2 - updatedY1 + 1;
            }
          }
        }
        reader.close();
      }
      
      // Done with all splits. Write output to disk
      LOG.info("Done with rasterization. Now writing the output");
      final FileSystem outFS = outPath.getFileSystem(params);
      
      LOG.info("Writing default empty image");
      // Write a default empty image to be displayed for non-generated tiles
      BufferedImage emptyImg = new BufferedImage(tileWidth, tileHeight,
          BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = new SimpleGraphics(emptyImg);
      g.setBackground(new Color(0, 0, 0, 0));
      g.clearRect(0, 0, tileWidth, tileHeight);
      g.dispose();

      // Write HTML file to browse the mutlielvel image
      OutputStream out = outFS.create(new Path(outPath, "default.png"));
      ImageIO.write(emptyImg, "png", out);
      out.close();

      // Add an HTML file that visualizes the result using Google Maps
      LOG.info("Writing the HTML viewer file");
      LineReader templateFileReader = new LineReader(MultilevelPlot.class
          .getResourceAsStream("/zoom_view.html"));
      PrintStream htmlOut = new PrintStream(outFS.create(new Path(outPath,
          "index.html")));
      Text line = new Text();
      while (templateFileReader.readLine(line) > 0) {
        String lineStr = line.toString();
        lineStr = lineStr.replace("#{TILE_WIDTH}", Integer.toString(tileWidth));
        lineStr = lineStr.replace("#{TILE_HEIGHT}",
            Integer.toString(tileHeight));
        lineStr = lineStr.replace("#{MAX_ZOOM}", Integer.toString(maxLevel));
        lineStr = lineStr.replace("#{MIN_ZOOM}", Integer.toString(minLevel));
        lineStr = lineStr.replace("#{TILE_URL}", "'tile_' + zoom + '_' + coord.x + '-' + coord.y + '.png'");

        htmlOut.println(lineStr);
      }
      templateFileReader.close();
      htmlOut.close();

      // Write the tiles
      final Entry<TileIndex, RasterLayer>[] entries =
          rasterLayers.entrySet().toArray(new Map.Entry[rasterLayers.size()]);
      // Clear the hash map to save memory as it is no longer needed
      rasterLayers.clear();
      int parallelism = params.getInt("parallel",
          Runtime.getRuntime().availableProcessors());
      Parallel.forEach(entries.length, new RunnableRange<Object>() {
        @Override
        public Object run(int i1, int i2) {
          try {
            Rasterizer rasterizer = rasterizerClass.newInstance();
            rasterizer.configure(params);
            for (int i = i1; i < i2; i++) {
              Map.Entry<TileIndex, RasterLayer> entry = entries[i];
              TileIndex key = entry.getKey();
              if (vflip)
                key.y = ((1 << key.level) - 1) - key.y;
              
              Path imagePath = new Path(outPath, key.getImageFileName());
              // Write this tile to an image
              FSDataOutputStream outFile = outFS.create(imagePath);
              rasterizer.writeImage(entry.getValue(), outFile, vflip);
              outFile.close();
              
              // Remove entry to allows GC to collect it
              entries[i] = null;
            }
            return null;
          } catch (InstantiationException e) {
            e.printStackTrace();
          } catch (IllegalAccessException e) {
            e.printStackTrace();
          } catch (IOException e) {
            e.printStackTrace();
          }
          return null;
        }
      }, parallelism);
    } catch (InstantiationException e) {
      throw new RuntimeException("Error creating rastierizer", e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Error creating rastierizer", e);
    }
  }
  
  public static Job plot(Path[] inPaths, Path outPath,
      Class<? extends Rasterizer> rasterizerClass, OperationsParams params)
      throws IOException, InterruptedException, ClassNotFoundException {
    if (params.getBoolean("showmem", false)) {
      // Run a thread that keeps track of used memory
      Thread memThread = new Thread(new Thread() {
        @Override
        public void run() {
          Runtime runtime = Runtime.getRuntime();  
          while(true) {
            try {
              Thread.sleep(60000);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
            runtime.gc();
            LOG.info("Memory usage: " + ((runtime.totalMemory() 
                - runtime.freeMemory()) / (1024*1024*1024)) + "GB.");
          }
        }
      });
      memThread.setDaemon(true);
      memThread.start();
    }
      
    // Decide how to run it based on range of levels to generate
    String[] strLevels = params.get("levels", "7").split("\\.\\.");
    int minLevel, maxLevel;
    if (strLevels.length == 1) {
      minLevel = 0;
      maxLevel = Integer.parseInt(strLevels[0]) - 1;
    } else {
      minLevel = Integer.parseInt(strLevels[0]);
      maxLevel = Integer.parseInt(strLevels[1]);
    }
    // Create an output directory that will hold the output of the two jobs
    FileSystem outFS = outPath.getFileSystem(params);
    outFS.mkdirs(outPath);
    
    Job runningJob = null;
    if (OperationsParams.isLocal(params, inPaths)) {
      // Plot local
      plotLocal(inPaths, outPath, rasterizerClass, params);
    } else {
      int maxLevelWithFlatPartitioning = params.getInt(FlatPartitioningLevelThreshold, 4);
      if (minLevel <= maxLevelWithFlatPartitioning) {
        OperationsParams flatPartitioning = new OperationsParams(params);
        flatPartitioning.set("levels", minLevel+".."+Math.min(maxLevelWithFlatPartitioning, maxLevel));
        flatPartitioning.set("partition", "flat");
        LOG.info("Using flat partitioning in levels "+flatPartitioning.get("levels"));
        runningJob = plotMapReduce(inPaths, new Path(outPath, "flat"), rasterizerClass, flatPartitioning);
      }
      if (maxLevel > maxLevelWithFlatPartitioning) {
        OperationsParams pyramidPartitioning = new OperationsParams(params);
        pyramidPartitioning.set("levels", Math.max(minLevel, maxLevelWithFlatPartitioning+1)+".."+maxLevel);
        pyramidPartitioning.set("partition", "pyramid");
        LOG.info("Using pyramid partitioning in levels "+pyramidPartitioning.get("levels"));
        runningJob = plotMapReduce(inPaths, new Path(outPath, "pyramid"), rasterizerClass, pyramidPartitioning);
      }
      // Write a new HTML file that displays both parts of the pyramid
      // Add an HTML file that visualizes the result using Google Maps
      LineReader templateFileReader = new LineReader(MultilevelPlot.class
          .getResourceAsStream("/zoom_view.html"));
      PrintStream htmlOut = new PrintStream(outFS.create(new Path(outPath,
          "index.html")));
      Text line = new Text();
      while (templateFileReader.readLine(line) > 0) {
        String lineStr = line.toString();
        lineStr = lineStr.replace("#{TILE_WIDTH}", Integer.toString(params.getInt("tilewidth", 256)));
        lineStr = lineStr.replace("#{TILE_HEIGHT}", Integer.toString(params.getInt("tileheight", 256)));
        lineStr = lineStr.replace("#{MAX_ZOOM}", Integer.toString(maxLevel));
        lineStr = lineStr.replace("#{MIN_ZOOM}", Integer.toString(minLevel));
        lineStr = lineStr.replace("#{TILE_URL}", "(zoom <= "+maxLevelWithFlatPartitioning+"? 'flat' : 'pyramid')+('/tile_' + zoom + '_' + coord.x + '-' + coord.y + '.png')");
        
        htmlOut.println(lineStr);
      }
      templateFileReader.close();
      htmlOut.close();
    }
    
    return runningJob;
  }
}
