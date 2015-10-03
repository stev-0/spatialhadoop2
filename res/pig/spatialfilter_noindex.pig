REGISTER pigeon-0.2.1.jar;
REGISTER jts-1.8.jar;
REGISTER esri-geometry-api-1.1.1.jar;

IMPORT 'pigeon_import.pig';

/*points = LOAD '$inputNodes' AS (node_id:long, lon:double, lat:double, nodeTags:map);*/

ways = LOAD '$inputWays' AS (way_id:long,geom:chararray,way_tags:map[]);

filtered_ways = FILTER ways BY
(
        (
                (ST_XMin(geom) < $maxX) AND (ST_XMin(geom) > $minX)
        )
        OR
        (
                (ST_XMax(geom) > $minX) AND (ST_XMax(geom) < $maxX)
        ) 
) AND (
        (
                (ST_YMin(geom) < $maxY) AND (ST_YMin(geom) > $minY)
        )
        OR
        (
                (ST_YMax(geom) > $minY) AND (ST_YMax(geom) < $maxY)
        )
);

STORE filtered_ways into '$outputWays' USING PigStorage('\t');
