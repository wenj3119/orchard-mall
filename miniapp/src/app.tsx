import type { PropsWithChildren } from 'react'
import { buildDiagnostic } from './api'
import './app.scss'
console.info('[orchard-build]', buildDiagnostic.buildId, buildDiagnostic.apiOrigin)
export default function App({ children }: PropsWithChildren) { return children }
