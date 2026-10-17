// עיצוב מפת הרחובות בסגנון גוגל מפות (בהיר / כהה) - אריחים וקטוריים חינמיים מ-OpenFreeMap, בלי מפתח
function mapStyle(dark) {
  var c = dark ? {
    bg: '#2C3042', urban: '#262A3A', water: '#17263C', park: '#263C3F', wood: '#2A3D3C', building: '#30354A',
    minor: '#3E465A', road: '#4A5468', main: '#56627A', motorway: '#6B7A99', casing: '#262A3A',
    rail: '#4A5060', border: '#5C6478', label: '#B8BCC4', road_label: '#9AA0A6', halo: '#1E2130', place: '#D0D3DA'
  } : {
    bg: '#F5F1E8', urban: '#EFEBE3', water: '#AADAFF', park: '#CDE8C5', wood: '#D3E7C6', building: '#E8E3DA',
    minor: '#FFFFFF', road: '#FFFFFF', main: '#FFFFFF', motorway: '#FCE6A9', casing: '#D9D3C7',
    rail: '#C9C4BB', border: '#B4AFA6', label: '#5F6368', road_label: '#70757A', halo: '#FFFFFF', place: '#3C4043'
  };
  var name = ['coalesce', ['get', 'name:he'], ['get', 'name']];
  var font = ['Noto Sans Regular'];
  function road(id, classes, color, w, minzoom) {
    return { id: id, type: 'line', source: 'omt', 'source-layer': 'transportation', minzoom: minzoom || 0,
      filter: ['in', ['get', 'class'], ['literal', classes]],
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': color, 'line-width': ['interpolate', ['exponential', 1.5], ['zoom'], 8, w[0], 12, w[1], 16, w[2], 18, w[3]] } };
  }
  return {
    version: 8,
    glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
    sources: { omt: { type: 'vector', url: 'https://tiles.openfreemap.org/planet' } },
    layers: [
      { id: 'bg', type: 'background', paint: { 'background-color': c.bg } },
      { id: 'urban', type: 'fill', source: 'omt', 'source-layer': 'landuse',
        filter: ['in', ['get', 'class'], ['literal', ['residential', 'suburb', 'neighbourhood', 'commercial', 'industrial', 'retail']]],
        paint: { 'fill-color': c.urban } },
      { id: 'wood', type: 'fill', source: 'omt', 'source-layer': 'landcover',
        filter: ['in', ['get', 'class'], ['literal', ['wood', 'forest', 'grass', 'farmland']]], paint: { 'fill-color': c.wood, 'fill-opacity': .6 } },
      { id: 'park', type: 'fill', source: 'omt', 'source-layer': 'park', paint: { 'fill-color': c.park } },
      { id: 'water', type: 'fill', source: 'omt', 'source-layer': 'water', paint: { 'fill-color': c.water } },
      { id: 'waterway', type: 'line', source: 'omt', 'source-layer': 'waterway', paint: { 'line-color': c.water, 'line-width': 1.2 } },
      { id: 'building', type: 'fill', source: 'omt', 'source-layer': 'building', minzoom: 14, paint: { 'fill-color': c.building } },
      { id: 'border', type: 'line', source: 'omt', 'source-layer': 'boundary', filter: ['<=', ['get', 'admin_level'], 4],
        paint: { 'line-color': c.border, 'line-width': 1, 'line-dasharray': [3, 2] } },
      // קווי מתאר לכבישים (רק בבהיר - כמו בגוגל)
      road('casing-main', ['motorway', 'trunk', 'primary', 'secondary'], c.casing, [1.5, 4, 12, 22]),
      road('minor', ['tertiary', 'minor', 'service', 'track'], c.minor, [0, .8, 6, 14], 11),
      road('road', ['secondary', 'tertiary'], c.road, [.5, 2, 9, 18], 9),
      road('main', ['primary', 'trunk'], c.main, [.8, 2.6, 10, 20]),
      road('motorway', ['motorway'], c.motorway, [1, 3, 11, 22]),
      { id: 'rail', type: 'line', source: 'omt', 'source-layer': 'transportation', minzoom: 11,
        filter: ['==', ['get', 'class'], 'rail'], paint: { 'line-color': c.rail, 'line-width': 1, 'line-dasharray': [4, 3] } },
      { id: 'road-label', type: 'symbol', source: 'omt', 'source-layer': 'transportation_name', minzoom: 13,
        layout: { 'symbol-placement': 'line', 'text-field': name, 'text-font': font, 'text-size': 12 },
        paint: { 'text-color': c.road_label, 'text-halo-color': c.halo, 'text-halo-width': 1.5 } },
      { id: 'place', type: 'symbol', source: 'omt', 'source-layer': 'place',
        filter: ['in', ['get', 'class'], ['literal', ['city', 'town', 'village', 'suburb', 'neighbourhood']]],
        layout: { 'text-field': name, 'text-font': font,
          'text-size': ['match', ['get', 'class'], 'city', 16, 'town', 14, 12] },
        paint: { 'text-color': c.place, 'text-halo-color': c.halo, 'text-halo-width': 1.5 } }
    ]
  };
}
