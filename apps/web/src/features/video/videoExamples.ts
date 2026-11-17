export interface VideoExample {
  id: string
  title: string
  src: string
  prompt: string
}

/**
 * Locally hosted demo clips. The prompt attached to each card is the exact text that produced
 * the clip, so "使用提示词" always replays a genuine input rather than a marketing caption.
 */
export const videoExamples: VideoExample[] = [
  {
    id: 'racetrack',
    title: 'Blob Racers on a Bright Track',
    src: '/videos/hero-racetrack.mp4',
    prompt: '一群圆滚滚的软萌小怪物开着彩色玩具赛车，在明亮的赛道上竞速，材质像搪胶玩具，'
      + '柔和影棚光，浅色背景，镜头平滑地跟随赛车平移，画面干净通透。',
  },
  {
    id: 'cake',
    title: 'A Birthday Cake Across the Apocalypse',
    src: '/videos/example-cake.mp4',
    prompt: '黑暗废弃城市的废墟中央立着一只插着点燃蜡烛的生日蛋糕，空气里漂浮着尘埃，'
      + '镜头缓慢推进，青绿色与琥珀色的电影感光影，写实风格。',
  },
]
