% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

**Example**

```esql
ROW message = "   some text  ",  color = " red "
| EVAL message = LTRIM(message)
| EVAL color = LTRIM(color)
| EVAL message = CONCAT("'", message, "'")
| EVAL color = CONCAT("'", color, "'")
```

| message:keyword | color:keyword |
| --- | --- |
| 'some text  ' | 'red ' |


