REGISTER /home/cloudera/osmpbf/osmpbfinputformat/lib/osmpbf-1.3.3.jar;
REGISTER /home/cloudera/osmpbf/importMR/osmpbfinputformat.jar;
REGISTER /mnt/hgfs/Share/pigeon/target/pigeon-1.0-SNAPSHOT.jar;
REGISTER /mnt/hgfs/Share/lib/esri-geometry-api-1.1.1.jar;

IMPORT '/mnt/hgfs/Share/pigeon_import.pig';

pbf_nodes = LOAD '/mnt/hgfs/Share/leeds.osm.pbf' USING io.github.gballet.pig.OSMPbfPigLoader('1') AS (id:long, lat:double, lon:double, nodeTags:map[]);

pbf_ways = LOAD '/mnt/hgfs/Share/leeds.osm.pbf' USING io.github.gballet.pig.OSMPbfPigLoader('2') AS (id:long, nodes:bag{(order:int, nodeid:long)}, tags:map[]);

pbf_ways = FOREACH pbf_ways
  GENERATE id AS way_id, FLATTEN(nodes), tags AS way_tags;

node_locations = FOREACH pbf_nodes
  GENERATE id, ST_MakePoint(lon, lat) AS location;

/* Join ways with nodes to find the location of each node (lat, lon)*/
joined_ways = JOIN node_locations BY id, pbf_ways BY nodes::nodeid PARALLEL 70;

describe pbf_nodes;
describe pbf_ways;
dump joined_ways;

