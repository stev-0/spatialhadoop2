/***********************************************************************
* Copyright (c) 2015 by Regents of the University of Minnesota.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which 
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*
*************************************************************************/

REGISTER osmpbf-1.3.3.jar;
REGISTER pigeon-1.0-SNAPSHOT.jar;
REGISTER esri-geometry-api-1.1.1.jar;
REGISTER piggybank.jar;
REGISTER spatialhadoop-2.3.jar;

IMPORT 'pigeon_import.pig';

/* Read and parse nodes */
xml_nodes = LOAD '$input'
  USING org.apache.pig.piggybank.storage.XMLLoader('node')
  AS (node:chararray);

parsed_nodes = FOREACH xml_nodes GENERATE FLATTEN(edu.umn.cs.spatialHadoop.osm.OSMNode(node));

flattened_nodes = FOREACH parsed_nodes
  GENERATE id AS nodeid, lat, lon, tags as nodeTags;

/* store nodes with interesting tags into a file */
interesting_nodes = FILTER flattened_nodes BY NOT IsEmpty(nodeTags) AND NOT (SIZE(nodeTags)==1 AND (nodeTags#'created_by' is not null OR nodeTags#'source' is not null));

/*interesting_nodes_geo = FOREACH interesting_nodes
  GENERATE node_id, ST_MakePoint(lon, lat) AS location, nodeTags;  NOT REQUIRED*/

interesting_nodes_shadoop = FOREACH interesting_nodes
  GENERATE nodeid, lon, lat, nodeTags;

node_locations = FOREACH flattened_nodes
  GENERATE nodeid, ST_MakePoint(lon, lat) AS location;

STORE interesting_nodes_shadoop into '$nodeOutput' USING PigStorage('\t');

/* Read and parse ways */
xml_ways = LOAD '$input' USING org.apache.pig.piggybank.storage.XMLLoader('way') AS (way:chararray);

parsed_ways = FOREACH xml_ways GENERATE edu.umn.cs.spatialHadoop.osm.OSMWay(way) AS way;

/* Keep only way_id with tags to be joined later*/
flattened_ways = FOREACH parsed_ways
  GENERATE way.id AS way_id, FLATTEN(way.nodes), way.tags AS way_tags;

/* Join ways with nodes to find the location of each node (lat, lon)*/
joined_ways = JOIN node_locations BY nodeid, flattened_ways BY nodes::node_id PARALLEL 70;

/* Group all node locations of each way by way ID*/
ways_with_nodes = GROUP joined_ways BY way_id PARALLEL 70;

/* For each way, generate a shape out of every list of points. Keep first and last node IDs to be able to connect ways later*/
ways_with_shapes = FOREACH ways_with_nodes {
  /* order points by position */
  ordered_asc = ORDER joined_ways BY pos ASC;
  first_node = LIMIT ordered_asc 1;
  ordered_desc = ORDER joined_ways BY pos DESC;
  last_node = LIMIT ordered_desc 1;
  tags = FOREACH joined_ways GENERATE way_tags;

  GENERATE group AS way_id, FLATTEN(first_node.nodeid) AS first_node_id,
     FLATTEN(last_node.nodeid) AS last_node_id,
     ST_MakeLinePolygon(ordered_asc.nodeid, ordered_asc.location) AS geom,
     FLATTEN(TOP(1, 0, tags)) AS way_tags;
};

ways_with_wkt_shapes = FOREACH ways_with_shapes GENERATE way_id, ST_AsText(geom), way_tags AS tags;

STORE ways_with_wkt_shapes into '$wayOutput' USING PigStorage('\t');