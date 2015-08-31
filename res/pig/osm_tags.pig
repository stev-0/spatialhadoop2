REGISTER spatialhadoop-2.4-rc1_debug.jar;
nodes = LOAD '/data/uknodes_tab' as (id:long, geom:chararray, nodeTags:map[]);
ways = LOAD '/data/ukways_tab' as (id:long, geom:chararray, wayTags:map[]);
describe nodes;
describe ways;
filterednodes = FILTER nodes BY edu.umn.cs.spatialHadoop.osm.HasTag(nodeTags,'shop','convenience,supermarket,bakery,butcher,newsa$
filteredways = FILTER ways BY edu.umn.cs.spatialHadoop.osm.HasTag(wayTags,'shop','convenience,supermarket,bakery,butcher,newsagen$
STORE filterednodes into '/data/uk_filterednodes' USING PigStorage('\t');
STORE filteredways into '/data/uk_filteredways' USING PigStorage('\t');