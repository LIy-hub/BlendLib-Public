# Run as a player: /function blendlib_runnable_examples:named_skins
# Client must launch with -Dblendlib.examples.namedSkins=true
summon blendlib_runnable_examples:layered_actor ~-1 ~ ~3 {CustomName:{text:"Ember"}}
summon blendlib_runnable_examples:layered_actor ~1 ~ ~3 {CustomName:{text:"Frost"}}
give @s blendlib_runnable_examples:animated_wand[minecraft:custom_name={text:"Ember"}] 1
give @s blendlib_runnable_examples:animated_wand[minecraft:custom_name={text:"Frost"}] 1
