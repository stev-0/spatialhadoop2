REGISTER /home/cloudera/osmpbf/osmpbfinputformat/lib/osmpbf-1.3.3.jar;
REGISTER /home/cloudera/osmpbf/importMR/osmpbfinputformat.jar;
REGISTER /mnt/hgfs/Share/pigeon/target/pigeon-1.0-SNAPSHOT.jar;
REGISTER /mnt/hgfs/Share/lib/esri-geometry-api-1.1.1.jar;

IMPORT '/mnt/hgfs/Share/pigeon_import.pig';

pbf_nodes = LOAD '/mnt/hgfs/Share/leeds.osm.pbf' USING io.github.gballet.pig.OSMPbfPigLoader('1') AS (id:long, lat:double, lon:double, nodeTags:map[]);

pbf_ways = LOAD '/mnt/hgfs/Share/leeds.osm.pbf' USING io.github.gballet.pig.OSMPbfPigLoader('2') AS (id:long, nodes:bag{(pos:int, nodeid:long)}, tags:map[]);

pbf_ways = FOREACH pbf_ways
  GENERATE id AS way_id, FLATTEN(nodes), tags AS way_tags;

node_locations = FOREACH pbf_nodes
  GENERATE id AS node_id, ST_MakePoint(lon, lat) AS location;

/* store nodes with interesting tags into a file */
interesting_nodes = FILTER pbf_nodes BY NOT IsEmpty(nodeTags) AND NOT (SIZE(nodeTags)==1 AND nodeTags#'created_by' is not null);

interesting_nodes_geo = FOREACH interesting_nodes
  GENERATE id AS node_id, ST_MakePoint(lon, lat) AS location, nodeTags as tags;

STORE interesting_nodes_geo into 'output/nodes' USING PigStorage(',');

/* Join ways with nodes to find the location of each node (lat, lon)*/
joined_ways = JOIN node_locations BY node_id, pbf_ways BY nodes::nodeid PARALLEL 70;

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

describe pbf_nodes;
describe pbf_ways;


