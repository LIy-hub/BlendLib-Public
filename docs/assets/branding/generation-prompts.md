# BlendLib brand asset generation

Tool: built-in image_gen. Selected folded B + Minecraft-inspired pixel lendLib. Generated raster PNGs; background variants are generated separately, not guaranteed pixel-identical.

## Initial 1

Use case: background-extraction. Edit the supplied approved BlendLib folded B icon. Remove ONLY the soft-white background, including the two interior triangular apertures, and make those pixels genuinely transparent using a PNG alpha channel. Preserve the complete graphite and light-gray B symbol EXACTLY: same silhouette, geometry, two gray returning faces, edges, proportions, position, canvas dimensions and all foreground colors. No crop, no redesign, no added lettering. IMPORTANT output actual RGBA transparency, NOT a checkerboard image, NOT an opaque black or white background. This is a production transparent icon export.

## Initial 2

Use case: logo-brand. Create a polished horizontal BlendLib wordmark PNG with a genuinely transparent alpha background, based on the attached approved icon. The attached folded B emblem must serve as the FIRST LETTER B of the word, immediately followed by the exact text "lendLib", so the complete visible name reads "BlendLib" ONCE. Do not duplicate a typographic B beside the emblem. Preserve the supplied B emblem's angular axonometric folded form, two triangular negative-space holes, dark graphite main planes and two neutral light gray right-hand returning planes. Place this emblem at the left of a single continuous typographic line. Draw the remaining letters l e n d L i b in refined Minecraft-inspired pixel typography: bold clean square grid, crisp stepped corners, restrained 8-bit game UI letter construction, no rough stone texture and no cracks. Case must be exactly lendLib with capital L only in Lib. The straight capital L / lowercase l ascenders should align with the main letter scale of B; carefully balance the emblem size with type so B belongs to the name rather than becoming a separate oversized icon. Flat graphite type, single monochrome neutral palette. Clear controlled spacing, professionally optically kerned. Premium, disciplined, minimal. Wide horizontal composition about 3:1 aspect ratio, entire name centered with generous transparent padding and no clipping. No subtitle, no number, no taglines, no separate icon above, no glow, no shadows, no outlines, no gradients, no colorful accent, no 3D stone Minecraft title treatment. True alpha background, NOT a checkerboard or a black rectangle.

## wordmark-transparent-fixed

Background extraction ONLY. The provided BlendLib wordmark currently has a FAKE light gray and white checkerboard baked into its background. Remove every checkerboard background pixel and export a truly transparent RGBA PNG with alpha=0 outside the foreground logo and inside letter holes. Preserve the entire existing folded B plus pixel-letter lendLib artwork exactly as supplied: layout, size, letterforms, casing, graphite and gray colors, shape, spacing, foreground fully opaque. No redesign, no new text. DO NOT draw any checkerboard or backdrop; actual alpha transparency is required. Keep same wide aspect ratio and full artwork unclipped. All empty regions must genuinely transparent, foreground solid opaque.

## wordmark-white

Precise background replacement ONLY. Preserve EXACTLY the supplied BlendLib wordmark artwork: folded angular gray B followed immediately by pixel typography lendLib, its shapes, spacing, alignment, gray colors and proportions. Replace the entire baked checkerboard background with perfectly uniform opaque PURE WHITE #FFFFFF, including background within all holes in B and letters. Keep same wide canvas aspect ratio and composition. No checkerboard, no transparency, no additional objects, no shadows, no gradients, no typography changes. Background pixels must pure white, foreground remains exactly as reference.

## icon-white

Precise background replacement ONLY. Preserve EXACTLY the supplied approved BlendLib folded B icon: same symbol geometry, proportions, angular silhouette, graphite main faces and two gray right returning planes. Replace the off-white background with perfectly uniform opaque PURE WHITE #FFFFFF, including both triangular holes within the B. Keep symbol position, scale and square canvas unchanged. No typography, no new details, no shadows, no gradients or texture, no transparency. Only change the background to pure white.

## Final wordmark transparent background extraction

Remove the white background from this logo. Transparent background. Output a transparent PNG cutout of the complete graphite BlendLib logo, keeping both the folded B emblem and all pixel letters. Keep all foreground artwork unchanged. Make the background and letter holes transparent.

QA: Read-only System.Drawing alpha-channel sampling, details in verification.json. The checkerboard-background intermediate drafts were excluded from the deliverables.
