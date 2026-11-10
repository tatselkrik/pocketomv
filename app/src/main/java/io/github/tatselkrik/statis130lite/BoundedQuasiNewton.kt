package io.github.tatselkrik.statis130lite

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/** Dense representation of limited-memory BFGS, generalized Cauchy point,
 * free-variable subspace minimization, and safeguarded More–Thuente line search.
 * The line search adapts SciPy 1.14.1 _dcsrch.py (BSD-3-Clause), license in assets/third-party/scipy-minpack2-LICENSE.txt.
 * Copyright (c) 2001-2002 Enthought, Inc. 2003-2024, SciPy Developers.
 * Dense algebra bounds the intended EFA work to at most 20 variables.
 */
internal object BoundedQuasiNewton {
    data class Evaluation(val value:Double,val gradient:DoubleArray)
    data class Result(val x:DoubleArray,val value:Double,val converged:Boolean,val iterations:Int,val reason:String)
    private data class Correction(val s:DoubleArray,val y:DoubleArray)
    private fun dot(a:DoubleArray,b:DoubleArray)=MatrixMath.dot(a,b)
    private fun multiply(a:Array<DoubleArray>,x:DoubleArray)=DoubleArray(x.size) { dot(a[it],x) }

    fun minimize(start:DoubleArray,lower:DoubleArray,upper:DoubleArray,
        objective:(DoubleArray)->Evaluation,factor:Double=1e7,limit:Int=100,memory:Int=5):Result {
        val n=start.size;require(n in 1..20&&lower.size==n&&upper.size==n)
        require(lower.indices.all { lower[it].isFinite()&&upper[it].isFinite()&&lower[it]<upper[it] })
        var x=DoubleArray(n) { start[it].coerceIn(lower[it],upper[it]) };var current=objective(x)
        val corrections=ArrayDeque<Correction>();var theta=1.0;var iteration=0;var restarts=0
        while(iteration<=limit) {
            require(current.value.isFinite()&&current.gradient.all(Double::isFinite))
            val projected=x.indices.maxOf { abs(x[it]-(x[it]-current.gradient[it]).coerceIn(lower[it],upper[it])) }
            if(projected==0.0)return Result(x,current.value,true,iteration,"projected gradient")
            if(iteration==limit)return Result(x,current.value,false,iteration,"iteration limit")
            val b=Array(n) { i -> DoubleArray(n) { j -> if(i==j)theta else 0.0 } }
            for(pair in corrections) {
                val bs=multiply(b,pair.s);val sbs=dot(pair.s,bs);val sy=dot(pair.s,pair.y)
                require(sbs>0&&sy>0)
                for(i in 0 until n)for(j in 0 until n)b[i][j]+=pair.y[i]*pair.y[j]/sy-bs[i]*bs[j]/sbs
            }
            val cauchy=generalizedCauchy(x,current.gradient,lower,upper,b)
            val free=x.indices.filter { cauchy[it]>lower[it]&&cauchy[it]<upper[it] }
            val displacement=DoubleArray(n) { cauchy[it]-x[it] }
            val bg=multiply(b,displacement)
            val candidate=cauchy.copyOf()
            if(free.isNotEmpty()) {
                val rhs=DoubleArray(free.size) { -current.gradient[free[it]]-bg[free[it]] }
                val sub=MatrixMath.qr(free.map { j -> DoubleArray(free.size) { b[free[it]][j] } }).solve(rhs)
                var fraction=1.0
                for(i in free.indices) {
                    val j=free[i]
                    if(sub[i]<0)fraction=min(fraction,(lower[j]-cauchy[j])/sub[i])
                    if(sub[i]>0)fraction=min(fraction,(upper[j]-cauchy[j])/sub[i])
                }
                for(i in free.indices)candidate[free[i]]=(cauchy[free[i]]+fraction*sub[i]).coerceIn(lower[free[i]],upper[free[i]])
            }
            val direction=DoubleArray(n) { candidate[it]-x[it] }
            val derivative=dot(current.gradient,direction)
            if(derivative>=0||!derivative.isFinite()) {
                if(corrections.isNotEmpty()&&restarts++<5) { corrections.clear();theta=1.0;continue }
                return Result(x,current.value,false,iteration,"no descent direction")
            }
            var maxStep=if(iteration==0)1.0 else 1e10
            for(i in 0 until n) {
                if(direction[i]<0)maxStep=min(maxStep,(lower[i]-x[i])/direction[i])
                if(direction[i]>0)maxStep=min(maxStep,(upper[i]-x[i])/direction[i])
            }
            val line=search(current.value,derivative,min(1.0,maxStep),maxStep) { step ->
                val trial=DoubleArray(n) { (x[it]+step*direction[it]).coerceIn(lower[it],upper[it]) }
                val evaluation=objective(trial)
                evaluation.value to dot(evaluation.gradient,direction)
            }
            if(line==null) {
                if(corrections.isNotEmpty()&&restarts++<5) { corrections.clear();theta=1.0;continue }
                return Result(x,current.value,false,iteration,"line search")
            }
            val next=DoubleArray(n) { (x[it]+line*direction[it]).coerceIn(lower[it],upper[it]) }
            val evaluated=objective(next);iteration++
            val decrease=current.value-evaluated.value
            if(decrease<=factor*Math.ulp(1.0)*max(1.0,max(abs(current.value),abs(evaluated.value))))
                return Result(next,evaluated.value,true,iteration,"relative objective change")
            val s=DoubleArray(n) { next[it]-x[it] };val y=DoubleArray(n) { evaluated.gradient[it]-current.gradient[it] }
            val sy=dot(s,y)
            if(sy>Math.ulp(1.0)*(-derivative*line)) {
                if(corrections.size==memory)corrections.removeFirst()
                corrections.addLast(Correction(s,y));theta=dot(y,y)/sy
            }
            x=next;current=evaluated
        }
        error("Unreachable")
    }

    private fun generalizedCauchy(x:DoubleArray,g:DoubleArray,lower:DoubleArray,upper:DoubleArray,b:Array<DoubleArray>):DoubleArray {
        val direction=DoubleArray(x.size) { -g[it] }
        val breaks=x.indices.map { i -> i to when {
            direction[i]<0 -> (lower[i]-x[i])/direction[i]
            direction[i]>0 -> (upper[i]-x[i])/direction[i]
            else -> Double.POSITIVE_INFINITY
        } }.sortedBy { it.second }
        val point=x.copyOf();var elapsed=0.0
        for((i,time)in breaks) {
            if(direction[i]==0.0)continue
            val offset=DoubleArray(x.size) { point[it]-x[it] }
            val gradient=DoubleArray(x.size) { g[it]+dot(b[it],offset) }
            val curvature=dot(direction,multiply(b,direction))
            if(curvature<=0)break
            val best=(-dot(gradient,direction)/curvature).coerceAtLeast(0.0)
            val interval=time-elapsed
            if(best<=interval) {
                for(j in x.indices)point[j]=(point[j]+best*direction[j]).coerceIn(lower[j],upper[j])
                return point
            }
            for(j in x.indices)point[j]=(point[j]+interval*direction[j]).coerceIn(lower[j],upper[j])
            point[i]=if(direction[i]<0)lower[i]else upper[i]
            direction[i]=0.0;elapsed=time
        }
        return point
    }

    private data class Endpoint(val step:Double,val value:Double,val slope:Double)
    private data class StepResult(val x:Endpoint,val y:Endpoint,val step:Double,val bracketed:Boolean)

    private fun search(f0:Double,g0:Double,initial:Double,maximum:Double,evaluate:(Double)->Pair<Double,Double>):Double? {
        if(initial<=0||g0>=0)return null
        val gtest=.001*g0;var stage=1;var bracketed=false
        var x=Endpoint(0.0,f0,g0);var y=x;var step=initial
        var minimum=0.0;var maximumInterval=5*step;var width=maximum;var oldWidth=2*maximum
        repeat(20) {
            val (f,g)=evaluate(step)
            if(!f.isFinite()||!g.isFinite())return null
            val sufficient=f0+step*gtest
            if(stage==1&&f<=sufficient&&g>=0)stage=2
            val warning=bracketed&&(step<=minimum||step>=maximumInterval||maximumInterval-minimum<=.1*maximumInterval)||
                step==maximum&&f<=sufficient&&g<=gtest||step==0.0&&(f>sufficient||g>=gtest)
            if(f<=sufficient&&abs(g)<=.9*(-g0)||warning)return step
            val update=if(stage==1&&f<=x.value&&f>sufficient) {
                val modified=updateStep(x.copy(value=x.value-x.step*gtest,slope=x.slope-gtest),
                    y.copy(value=y.value-y.step*gtest,slope=y.slope-gtest),step,f-step*gtest,g-gtest,bracketed,minimum,maximumInterval)
                modified.copy(x=modified.x.let { it.copy(value=it.value+it.step*gtest,slope=it.slope+gtest) },
                    y=modified.y.let { it.copy(value=it.value+it.step*gtest,slope=it.slope+gtest) })
            } else updateStep(x,y,step,f,g,bracketed,minimum,maximumInterval)
            x=update.x;y=update.y;step=update.step;bracketed=update.bracketed
            if(bracketed) {
                if(abs(y.step-x.step)>=.66*oldWidth)step=x.step+.5*(y.step-x.step)
                oldWidth=width;width=abs(y.step-x.step)
                minimum=min(x.step,y.step);maximumInterval=max(x.step,y.step)
            } else { minimum=step+1.1*(step-x.step);maximumInterval=step+4*(step-x.step) }
            step=step.coerceIn(0.0,maximum)
            if(bracketed&&(step<=minimum||step>=maximumInterval||maximumInterval-minimum<=.1*maximumInterval))step=x.step
            if(!step.isFinite())return null
        }
        return null
    }

    private fun updateStep(x:Endpoint,y:Endpoint,step:Double,f:Double,g:Double,bracket:Boolean,minimum:Double,maximum:Double):StepResult {
        val opposite=sign(g)*sign(x.slope)<0;var bracketed=bracket
        val predicted:Double
        if(f>x.value) {
            val theta=3*(x.value-f)/(step-x.step)+x.slope+g;val scale=max(abs(theta),max(abs(x.slope),abs(g)))
            var gamma=scale*sqrt(max(0.0,(theta/scale)*(theta/scale)-(x.slope/scale)*(g/scale)))
            if(step<x.step)gamma= -gamma
            val cubic=x.step+((gamma-x.slope)+theta)/(((gamma-x.slope)+gamma)+g)*(step-x.step)
            val quadratic=x.step+(x.slope/((x.value-f)/(step-x.step)+x.slope)/2)*(step-x.step)
            predicted=if(abs(cubic-x.step)<=abs(quadratic-x.step))cubic else cubic+(quadratic-cubic)/2
            bracketed=true
        } else if(opposite) {
            val theta=3*(x.value-f)/(step-x.step)+x.slope+g;val scale=max(abs(theta),max(abs(x.slope),abs(g)))
            var gamma=scale*sqrt(max(0.0,(theta/scale)*(theta/scale)-(x.slope/scale)*(g/scale)))
            if(step>x.step)gamma= -gamma
            val cubic=step+((gamma-g)+theta)/(((gamma-g)+gamma)+x.slope)*(x.step-step)
            val secant=step+g/(g-x.slope)*(x.step-step)
            predicted=if(abs(cubic-step)>abs(secant-step))cubic else secant
            bracketed=true
        } else if(abs(g)<abs(x.slope)) {
            val theta=3*(x.value-f)/(step-x.step)+x.slope+g;val scale=max(abs(theta),max(abs(x.slope),abs(g)))
            var gamma=scale*sqrt(max(0.0,(theta/scale)*(theta/scale)-(x.slope/scale)*(g/scale)))
            if(step>x.step)gamma= -gamma
            val ratio=((gamma-g)+theta)/((gamma+(x.slope-g))+gamma)
            val cubic=if(ratio<0&&gamma!=0.0)step+ratio*(x.step-step)else if(step>x.step)maximum else minimum
            val secant=step+g/(g-x.slope)*(x.step-step)
            var chosen=if(bracketed) { if(abs(cubic-step)<abs(secant-step))cubic else secant }
                else { if(abs(cubic-step)>abs(secant-step))cubic else secant }
            chosen=if(bracketed) {
                if(step>x.step)min(step+.66*(y.step-step),chosen)else max(step+.66*(y.step-step),chosen)
            } else chosen.coerceIn(minimum,maximum)
            predicted=chosen
        } else if(bracketed) {
            val theta=3*(f-y.value)/(y.step-step)+y.slope+g;val scale=max(abs(theta),max(abs(y.slope),abs(g)))
            var gamma=scale*sqrt(max(0.0,(theta/scale)*(theta/scale)-(y.slope/scale)*(g/scale)))
            if(step>y.step)gamma= -gamma
            predicted=step+((gamma-g)+theta)/(((gamma-g)+gamma)+y.slope)*(y.step-step)
        } else predicted=if(step>x.step)maximum else minimum
        val newY=if(f>x.value)Endpoint(step,f,g)else if(opposite)x else y
        val newX=if(f>x.value)x else Endpoint(step,f,g)
        return StepResult(newX,newY,predicted,bracketed)
    }
}
