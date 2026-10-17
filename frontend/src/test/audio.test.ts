import { describe, expect, it } from 'vitest'
import { bytesToBase64, encodePcm16Le } from '../lib/audio'
describe('PCM audio encoding',()=>{it('writes clipped PCM16 little endian samples',()=>{expect([...encodePcm16Le(new Float32Array([-1,0,1,2]))]).toEqual([0,128,0,0,255,127,255,127])});it('encodes 96 KB without argument-limit errors',()=>{const encoded=bytesToBase64(new Uint8Array(96*1024));expect(atob(encoded)).toHaveLength(96*1024)})})
