REGISTER pigeon-0.2.1.jar;
REGISTER jts-1.8.jar;
REGISTER esri-geometry-api-1.1.1.jar;

/*points = LOAD '$inputNodes' AS (node_id:long, lon:double, lat:double, nodeTags:map);*/
ways = LOAD '$inputWays' AS (way_id:long,geom:chararray, way_tags:map);

filtered_ways = FILTER ways BY (ST_XMin(geom) < $maxX OR ST_XMax(geom) > $minX) AND (ST_YMin(geom) < $maxY OR ST_YMax(geom) > $minY)
STORE ways_with_wkt_shapes into '$outputWays' USING PigStorage('\t');
