REGISTER spatialhadoop-2.4-rc1.jar;
nodes = LOAD '$nodeInput' USING PigStorage as (id:long, lon:long, lat:long, nodeTags:map[chararray]);
ways = LOAD '$wayInput' USING PigStorage as (id:long, geom:chararray, wayTags:map[chararray]);
cleannodes = FILTER nodes BY nodeTags is not null;
cleanways = FILTER ways BY wayTags is not null;
filterednodes = FILTER cleannodes BY edu.umn.cs.spatialHadoop.osm.HasTag(nodeTags,'shop','convenience,supermarket,bakery,butcher,newsagent,greengrocer,fishmonger,seafood';
filteredways = FILTER cleanways BY edu.umn.cs.spatialHadoop.osm.HasTag(wayTags,'shop','convenience,supermarket,bakery,butcher,newsagent,greengrocer,fishmonger,seafood';
STORE filterednodes into '$nodeOutput' using PigStorage('\t');
STORE filteredways into '$wayOutput' USING PigStorage('\t');