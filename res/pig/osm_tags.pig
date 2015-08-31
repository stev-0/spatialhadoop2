REGISTER spatialhadoop-2.4-rc1.jar;
nodes = LOAD '/data/uknodes_tab' USING PigStorage as (id:long, lon:int, lat:int, nodeTags:map[chararray]);
ways = LOAD '/data/ukways_tab' USING PigStorage as (id:long, geom:chararray, wayTags:map[chararray]);
cleannodes = FILTER nodes BY nodeTags is not null;
cleanways = FILTER ways BY wayTags is not null;
filterednodes = FILTER cleannodes BY edu.umn.cs.spatialHadoop.osm.HasTag(nodeTags,'shop','convenience,supermarket,bakery,butcher,$
filteredways = FILTER cleanways BY edu.umn.cs.spatialHadoop.osm.HasTag(wayTags,'shop','convenience,supermarket,bakery,butcher,new$STORE filterednodes into '/data/uk_filterednodes' USING PigStorage('\t');
STORE filteredways into '/data/uk_filteredways' USING PigStorage('\t');