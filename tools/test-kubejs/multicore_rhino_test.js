// Test only (RhinoTests): copy into <game dir>/kubejs/server_scripts. Never install on a real server.
// Arrow functions on purpose: Rhino takes its own lock before it enters the nested script function.
let multicoreRhinoCount = 0
EntityEvents.spawned('minecraft:item', event => {
  const mark = () => {
    multicoreRhinoCount++
    event.entity.addTag('multicore_rhino')
  }
  mark()
  // Now and then read a block entity in another dimension: the script waits for that dimension's thread.
  if (multicoreRhinoCount % 97 === 0) {
    event.server.getLevel(String(event.level.dimension) === 'minecraft:the_nether' ? 'minecraft:overworld' : 'minecraft:the_nether')
      .getBlock(8, 120, 8).entity
  }
})
