package com.example.lib



////Поиск свободных номеров
fun main() {
    val a = readln().toInt()
    val BB = mutableListOf<Int>()
    var RE3=0
    for (i in 0 until a){
        var b = readln().toInt()
        //println("$i $b")
        //println(b-RE3)
        if (b-RE3>1){
            //print(b-RE3)
            val CC=b-(b-RE3)
            for (ii in 0 until b-RE3-1){
                //print(CC+ii+1)
                BB.add(CC+ii+1)
            }
        }
        /*else{
            pass
        }*/
        //println(BB)
        RE3=b
    }
    //println(BB)
    for (i in BB){
        println(i)
    }
}